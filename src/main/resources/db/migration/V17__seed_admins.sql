-- Bootstrap internal platform admins.
-- Passwords are BCrypt hashes generated offline.

INSERT INTO internal_admins (
    id,
    email,
    password_hash,
    role,
    display_name,
    created_at,
    updated_at
)
VALUES
(
    '01KWNZPN4V3VABFRMD5NTHHBXG',
    'admin@subpilot.co',
    '$2a$12$R2Gffn.LBGtWn2EOCBp35.sR0TOj2w2tGvMZ3ukrkakEU25rD/2L2',
    'super_admin',
    'Platform Super Admin',
    NOW(),
    NOW()
),
(
    '01KWNZKZ5M4231ZERRN820FMGY',
    'ops@subpilot.co',
    '$2a$12$kGSqyr0LYteqd6WlV/NJj.OCZmrBfvAiMF5kEuF4gor9UFRC1gQSu',
    'ops_admin',
    'Operations Admin',
    NOW(),
    NOW()
)
ON CONFLICT (email) DO NOTHING;