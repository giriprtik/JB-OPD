-- observed_value is now stored AES-GCM encrypted (enc:<key>:<base64>), which is far longer than 50 chars.
-- Input length is still capped at 50 by the API validation.
ALTER TABLE lab_results ALTER COLUMN observed_value TYPE TEXT;
