CREATE TABLE IF NOT EXISTS public.unique_id_mapping
(
	id BIGSERIAL PRIMARY KEY,
    app_id TEXT NOT NULL,
    installation_id TEXT NOT NULL,
    user_id TEXT,
    unique_id TEXT NOT NULL,
    is_deadend BOOLEAN NOT NULL,
    is_merged BOOLEAN NOT NULL,
    created_tstamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
	deadend_tstamp TIMESTAMP WITH TIME ZONE,
	merged_tstamp TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_unique_id_by_app_id_user_id
    ON public.unique_id_mapping USING btree
    (app_id, user_id);

CREATE INDEX IF NOT EXISTS idx_unique_id_by_app_id_installation_id_user_id
    ON public.unique_id_mapping USING btree
    (app_id, installation_id, user_id);

CREATE OR REPLACE FUNCTION _get_unique_id_by_installation_id(
    p_app_id TEXT,
    p_installation_id TEXT
) RETURNS RECORD AS
$$
DECLARE
  v_unique_id TEXT;
BEGIN
  SELECT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id IS NULL;
  IF v_unique_id IS NOT NULL THEN
    RETURN (v_unique_id, FALSE);
  END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_app_id || '_' || p_installation_id));

  -- We tried this optimistically to avoid locking in most cases,
  -- but need to repeat it under lock if it fails.
  SELECT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id IS NULL;
  IF v_unique_id IS NOT NULL THEN
    RETURN (v_unique_id, FALSE);
  END IF;

  -- When inserting (installation_id, user_id) pair, we guarantee that (installation_id, NULL) will also be inserted
  -- Since (installation_id, NULL) is not found, we are sure (installation_id, user_id) does not exist either.
  -- So, we add new unique id
  v_unique_id = gen_random_uuid();
  INSERT INTO unique_id_mapping
  (app_id, installation_id, user_id, unique_id, is_deadend, is_merged)
  VALUES
  (p_app_id, p_installation_id, NULL, v_unique_id, FALSE, FALSE);

  RETURN (v_unique_id, TRUE);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION _get_unique_id_by_user_id(
    p_app_id TEXT,
    p_user_id TEXT
) RETURNS RECORD AS
$$
DECLARE
  v_unique_id TEXT;
BEGIN
  -- Sanity check that we only have 1 unique id (this will break if it returns > 1 rows)
  SELECT DISTINCT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND user_id = p_user_id;

  -- Since both (installation_id, NULL) and (NULL, user_id) pairs could come at the same time and get different unique id,
  -- we decided to not support adding new unique_id by (NULL, user_id).
  -- In the future, we can delay those events until (installation_id, user_id) comes.
  IF v_unique_id IS NULL THEN
    RAISE EXCEPTION 'registration of unique id not supported by (null, user_id) yet!';
  END IF;
  RETURN (v_unique_id, FALSE);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION _get_unique_id_by_installation_id_and_user_id(
    p_app_id TEXT,
    p_installation_id TEXT,
    p_user_id TEXT
) RETURNS RECORD AS
$$
DECLARE
  v_installation_id TEXT;
  v_unique_id TEXT;
  v_is_clean BOOLEAN;
  v_is_new_user BOOLEAN;
BEGIN
  v_is_new_user := FALSE;
  SELECT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id = p_user_id;
  IF v_unique_id IS NOT NULL THEN
    RETURN (v_unique_id, v_is_new_user);
  END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_app_id || '_i:' || p_installation_id));
  PERFORM pg_advisory_xact_lock(hashtext(p_app_id || '_u:' || p_user_id));
  -- We tried this optimistically to avoid locking in most cases,
  -- but need to repeat it under lock if it fails.
  SELECT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id = p_user_id;
  IF v_unique_id IS NOT NULL THEN
    RETURN (v_unique_id, v_is_new_user);
  END IF;

  -- Sanity check that we only have 1 unique id (this will break if it returns > 1 rows)
  SELECT DISTINCT unique_id INTO v_unique_id
  FROM unique_id_mapping
  WHERE app_id = p_app_id AND user_id = p_user_id;

  -- If we found a row by user_id, it means existing user is playing on new device for the first time
  IF v_unique_id IS NOT NULL THEN

    SELECT installation_id INTO v_installation_id
    FROM unique_id_mapping
    WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id IS NULL;

    -- Check if (installation_id, NULL) already exists for current device.
    -- If it doesn't, set deadend if it is not merged and deadend already
    -- If it doesn't, insert and set merged flag with existist unique_id
    IF v_installation_id IS NOT NULL THEN
      UPDATE unique_id_mapping SET is_deadend = TRUE, deadend_tstamp = NOW()
      WHERE app_id = p_app_id AND installation_id = v_installation_id AND user_id IS NULL AND NOT is_merged AND NOT is_deadend;
    ELSE
      INSERT INTO unique_id_mapping
      (app_id, installation_id, user_id, unique_id, is_deadend, is_merged, merged_tstamp)
      VALUES
      (p_app_id, p_installation_id, NULL, v_unique_id, FALSE, TRUE, NOW());
    END IF;
  -- Search by (installation_id, NULL)
  ELSE
    SELECT unique_id, NOT is_merged AND NOT is_deadend INTO v_unique_id, v_is_clean
    FROM unique_id_mapping
    WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id IS NULL;

    -- New user, generate unique_id, and insert merged (installation_id, NULL)
    IF v_unique_id IS NULL THEN
      v_unique_id = gen_random_uuid();
      v_is_new_user := TRUE;

      INSERT INTO unique_id_mapping
      (app_id, installation_id, user_id, unique_id, is_deadend, is_merged, merged_tstamp)
      VALUES
      (p_app_id, p_installation_id, NULL, v_unique_id, FALSE, TRUE, NOW());

    -- Device exists. If it is not merged and it is not deadend, mark as merged and reuse unique_id, it is linking
    -- Otherwise, generate unique_id, it is not linking
    ELSE
      IF v_is_clean THEN
        UPDATE unique_id_mapping SET is_merged = TRUE, merged_tstamp = NOW()
        WHERE app_id = p_app_id AND installation_id = p_installation_id AND user_id IS NULL;
      ELSE
        v_unique_id = gen_random_uuid();
        v_is_new_user := TRUE;
      END IF;
    END IF;

  END IF;

  -- Finally, add (installation_id, user_id)
  INSERT INTO unique_id_mapping
  (app_id, installation_id, user_id, unique_id, is_deadend, is_merged)
  VALUES
  (p_app_id, p_installation_id, p_user_id, v_unique_id, FALSE, FALSE);

  RETURN (v_unique_id, v_is_new_user);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION get_unique_id(
    p_app_id TEXT,
    p_installation_id TEXT,
    p_user_id TEXT
) RETURNS RECORD AS
$$
BEGIN
  IF p_installation_id IS NULL AND p_user_id IS NULL THEN
    RAISE EXCEPTION 'Must set at least installation_id or user_id!';
  END IF;

  IF p_installation_id IS NULL THEN
    RETURN _get_unique_id_by_user_id(p_app_id, p_user_id);
  END IF;

  IF p_user_id IS NULL THEN
    RETURN _get_unique_id_by_installation_id(p_app_id, p_installation_id);
  END IF;

  RETURN _get_unique_id_by_installation_id_and_user_id(p_app_id, p_installation_id, p_user_id);

END;
$$ LANGUAGE plpgsql;