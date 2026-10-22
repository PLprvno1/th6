
-- ============================================================
-- PhotoVault - Supabase test database reset and setup
-- Version: 2
--
-- WARNING:
-- First run can delete all rows and table-owned objects from:
--   public.photos
--   public.albums
--   public.users
--
-- Does NOT delete auth.users or Storage files.
-- photos_bucket is configured as PUBLIC.
-- Run only in the intended TEST Supabase project.
-- ============================================================

BEGIN;

-- ============================================================
-- 1. PREFLIGHT: validate Supabase Storage and reset dependencies
-- ============================================================

DO $$
DECLARE
    v_rel regclass;
    v_relkind "char";
    v_name text;
    v_problem text;
BEGIN
    IF to_regclass('storage.buckets') IS NULL
       OR to_regclass('storage.objects') IS NULL THEN
        RAISE EXCEPTION
            'Supabase Storage is unavailable. Enable Storage before running this script.';
    END IF;

    -- These names must refer to tables, not views or other objects.
    FOREACH v_name IN ARRAY ARRAY['photos', 'albums', 'users']
    LOOP
        v_rel := to_regclass('public.' || quote_ident(v_name));

        IF v_rel IS NOT NULL THEN
            SELECT c.relkind
              INTO v_relkind
              FROM pg_class c
             WHERE c.oid = v_rel;

            IF v_relkind NOT IN ('r', 'p') THEN
                RAISE EXCEPTION
                    'public.% exists but is not a table. No reset was performed.',
                    v_name;
            END IF;
        END IF;
    END LOOP;

    -- Stop if another table references one of the tables to be reset.
    SELECT string_agg(
               format('%s (constraint %I)', c.conrelid::regclass, c.conname),
               ', '
           )
      INTO v_problem
      FROM pg_constraint c
     WHERE c.contype = 'f'
       AND c.confrelid IN (
           to_regclass('public.photos'),
           to_regclass('public.albums'),
           to_regclass('public.users')
       )
       AND c.conrelid NOT IN (
           to_regclass('public.photos'),
           to_regclass('public.albums'),
           to_regclass('public.users')
       );

    IF v_problem IS NOT NULL THEN
        RAISE EXCEPTION
            'Tables to reset are referenced by external foreign keys: %. Resolve these dependencies before retrying.',
            v_problem;
    END IF;

    -- Avoid silently dropping views that depend on the reset tables.
    SELECT string_agg(DISTINCT v.oid::regclass::text, ', ')
      INTO v_problem
      FROM pg_depend d
      JOIN pg_rewrite r
        ON d.classid = 'pg_rewrite'::regclass
       AND d.objid = r.oid
      JOIN pg_class v
        ON v.oid = r.ev_class
     WHERE d.refobjid IN (
           to_regclass('public.photos'),
           to_regclass('public.albums'),
           to_regclass('public.users')
       )
       AND v.oid NOT IN (
           to_regclass('public.photos'),
           to_regclass('public.albums'),
           to_regclass('public.users')
       )
       AND v.relkind IN ('v', 'm');

    IF v_problem IS NOT NULL THEN
        RAISE EXCEPTION
            'Views/materialized views depend on tables to reset: %. Resolve these dependencies before retrying.',
            v_problem;
    END IF;
END
$$;


-- ============================================================
-- 2. REMOVE ONLY KNOWN LEGACY PHOTOVAULT STORAGE POLICIES
--
-- These exact policy names were reported by Supabase.
-- Do not remove unrelated policies belonging to other buckets.
-- ============================================================

DROP POLICY IF EXISTS photos_bucket_1y7mmp4_0
    ON storage.objects;

DROP POLICY IF EXISTS photos_bucket_1y7mmp4_1
    ON storage.objects;

DROP POLICY IF EXISTS photos_bucket_1y7mmp4_2
    ON storage.objects;

DROP POLICY IF EXISTS photos_bucket_1y7mmp4_3
    ON storage.objects;

-- Remove policies from earlier versions of this app's SQL.
DROP POLICY IF EXISTS photovault_photos_bucket_select
    ON storage.objects;

DROP POLICY IF EXISTS photovault_photos_bucket_insert
    ON storage.objects;

DROP POLICY IF EXISTS photovault_photos_bucket_update
    ON storage.objects;

DROP POLICY IF EXISTS photovault_photos_bucket_delete
    ON storage.objects;


-- ============================================================
-- 3. RESET THE THREE PHOTOVAULT TABLES
--
-- No CASCADE. The preflight above blocks known external
-- foreign-key and view dependencies.
-- ============================================================

DROP TABLE IF EXISTS public.photos;
DROP TABLE IF EXISTS public.albums;
DROP TABLE IF EXISTS public.users;


-- ============================================================
-- 4. CREATE PHOTOS TABLE
-- Matches the Photo model/source contract in the report.
-- ============================================================

CREATE TABLE public.photos (
    id                  text PRIMARY KEY
                        DEFAULT (gen_random_uuid())::text,

    user_id             uuid NOT NULL
                        REFERENCES auth.users(id)
                        ON DELETE CASCADE,

    title               text NOT NULL DEFAULT '',
    media_url           text NOT NULL,
    media_type          text NOT NULL DEFAULT 'image',

    created_at          timestamptz NOT NULL DEFAULT now(),

    is_favorite         boolean NOT NULL DEFAULT false,
    is_trashed          boolean NOT NULL DEFAULT false,
    trashed_at          bigint NOT NULL DEFAULT 0,

    local_media_id      bigint NOT NULL DEFAULT 0,
    local_media_uri     text NOT NULL DEFAULT '',
    local_date_modified bigint NOT NULL DEFAULT 0,

    album_ids           text[] NOT NULL DEFAULT '{}',

    storage_provider    text NOT NULL DEFAULT 'supabase',
    content_hash        text,
    storage_path        text NOT NULL DEFAULT ''
);


-- ============================================================
-- 5. CREATE ALBUMS TABLE
-- Album membership remains stored in photos.album_ids,
-- matching the current Android implementation.
-- ============================================================

CREATE TABLE public.albums (
    id              text PRIMARY KEY,

    owner_uid       uuid NOT NULL
                    REFERENCES auth.users(id)
                    ON DELETE CASCADE,

    name            text NOT NULL,
    created_at      bigint NOT NULL,
    cover_image_url text NOT NULL DEFAULT '',
    photo_count     integer NOT NULL DEFAULT 0
);


-- ============================================================
-- 6. CREATE USERS PROFILE TABLE
-- Authentication credentials remain in Supabase Auth.
-- ============================================================

CREATE TABLE public.users (
    uid          uuid PRIMARY KEY
                 REFERENCES auth.users(id)
                 ON DELETE CASCADE,

    display_name text NOT NULL DEFAULT '',
    email        text NOT NULL DEFAULT '',
    photo_url    text NOT NULL DEFAULT '',
    created_at   bigint NOT NULL DEFAULT 0
);


-- ============================================================
-- 7. INDEXES
-- ============================================================

CREATE INDEX photos_user_id_idx
    ON public.photos(user_id);

CREATE INDEX albums_owner_uid_idx
    ON public.albums(owner_uid);


-- ============================================================
-- 8. ENABLE ROW LEVEL SECURITY
-- ============================================================

ALTER TABLE public.photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.albums ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;


-- ============================================================
-- 9. RESET PHOTO METADATA POLICIES
-- ============================================================

DROP POLICY IF EXISTS photos_select_own ON public.photos;
DROP POLICY IF EXISTS photos_insert_own ON public.photos;
DROP POLICY IF EXISTS photos_update_own ON public.photos;
DROP POLICY IF EXISTS photos_delete_own ON public.photos;

CREATE POLICY photos_select_own
    ON public.photos
    FOR SELECT
    TO authenticated
    USING (user_id = auth.uid());

CREATE POLICY photos_insert_own
    ON public.photos
    FOR INSERT
    TO authenticated
    WITH CHECK (user_id = auth.uid());

CREATE POLICY photos_update_own
    ON public.photos
    FOR UPDATE
    TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

CREATE POLICY photos_delete_own
    ON public.photos
    FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());


-- ============================================================
-- 10. RESET ALBUM POLICIES
-- ============================================================

DROP POLICY IF EXISTS albums_select_own ON public.albums;
DROP POLICY IF EXISTS albums_insert_own ON public.albums;
DROP POLICY IF EXISTS albums_update_own ON public.albums;
DROP POLICY IF EXISTS albums_delete_own ON public.albums;

CREATE POLICY albums_select_own
    ON public.albums
    FOR SELECT
    TO authenticated
    USING (owner_uid = auth.uid());

CREATE POLICY albums_insert_own
    ON public.albums
    FOR INSERT
    TO authenticated
    WITH CHECK (owner_uid = auth.uid());

CREATE POLICY albums_update_own
    ON public.albums
    FOR UPDATE
    TO authenticated
    USING (owner_uid = auth.uid())
    WITH CHECK (owner_uid = auth.uid());

CREATE POLICY albums_delete_own
    ON public.albums
    FOR DELETE
    TO authenticated
    USING (owner_uid = auth.uid());


-- ============================================================
-- 11. RESET PROFILE POLICIES
-- ============================================================

DROP POLICY IF EXISTS users_select_own ON public.users;
DROP POLICY IF EXISTS users_insert_own ON public.users;
DROP POLICY IF EXISTS users_update_own ON public.users;
DROP POLICY IF EXISTS users_delete_own ON public.users;

CREATE POLICY users_select_own
    ON public.users
    FOR SELECT
    TO authenticated
    USING (uid = auth.uid());

CREATE POLICY users_insert_own
    ON public.users
    FOR INSERT
    TO authenticated
    WITH CHECK (uid = auth.uid());

CREATE POLICY users_update_own
    ON public.users
    FOR UPDATE
    TO authenticated
    USING (uid = auth.uid())
    WITH CHECK (uid = auth.uid());


-- ============================================================
-- 12. TABLE PRIVILEGES
-- ============================================================

REVOKE ALL ON public.photos, public.albums, public.users
    FROM anon, PUBLIC;

GRANT USAGE ON SCHEMA public TO authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON public.photos, public.albums
    TO authenticated;

GRANT SELECT, INSERT, UPDATE
    ON public.users
    TO authenticated;


-- ============================================================
-- 13. CREATE OR UPDATE THE PHOTOVAULT STORAGE BUCKET
--
-- Public bucket is intentional for the current app design.
-- Files already in Storage are not deleted by this SQL.
-- ============================================================

INSERT INTO storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
VALUES (
    'photos_bucket',
    'photos_bucket',
    true,
    52428800,
    NULL
)
ON CONFLICT (id)
DO UPDATE SET
    public = true,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = NULL;


-- ============================================================
-- 14. STORAGE OBJECT POLICIES
--
-- Authenticated upload/upsert/delete is scoped to the current
-- user's objects in photos_bucket.
-- Public URL reading is enabled by the bucket's public setting.
-- ============================================================

CREATE POLICY photovault_photos_bucket_select
    ON storage.objects
    FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'photos_bucket'
        AND owner_id = (SELECT auth.uid()::text)
    );

CREATE POLICY photovault_photos_bucket_insert
    ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'photos_bucket'
        AND owner_id = (SELECT auth.uid()::text)
    );

CREATE POLICY photovault_photos_bucket_update
    ON storage.objects
    FOR UPDATE
    TO authenticated
    USING (
        bucket_id = 'photos_bucket'
        AND owner_id = (SELECT auth.uid()::text)
    )
    WITH CHECK (
        bucket_id = 'photos_bucket'
        AND owner_id = (SELECT auth.uid()::text)
    );

CREATE POLICY photovault_photos_bucket_delete
    ON storage.objects
    FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'photos_bucket'
        AND owner_id = (SELECT auth.uid()::text)
    );

GRANT SELECT, INSERT, UPDATE, DELETE
    ON storage.objects
    TO authenticated;


-- ============================================================
-- 15. MARK THE INSTALLED SCHEMA
-- ============================================================

COMMENT ON TABLE public.photos
    IS 'PhotoVault final reset schema v2';

COMMENT ON TABLE public.albums
    IS 'PhotoVault final reset schema v2';

COMMENT ON TABLE public.users
    IS 'PhotoVault final reset schema v2';


-- Refresh PostgREST's schema cache.
NOTIFY pgrst, 'reload schema';

COMMIT;
