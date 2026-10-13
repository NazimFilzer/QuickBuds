CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at TEXT NOT NULL,          -- UTC, ISO 8601
  sender TEXT NOT NULL,      -- salted hash of the sender's IP, for the rate limit only
  app TEXT NOT NULL,         -- "QuickBuds 4.10.0 Android" or "... Windows"
  model TEXT NOT NULL,
  categories TEXT NOT NULL,  -- comma-separated, the form's words
  description TEXT NOT NULL,
  crash INTEGER NOT NULL,    -- 1: sent from the crash dialog
  log TEXT,                  -- null when the person turned the log off
  status TEXT NOT NULL DEFAULT 'new',  -- new, working, done, unsupported, no_log, no_description, needs_btsnoop
  note TEXT                  -- the developer's one-line reason for the status
);
CREATE INDEX IF NOT EXISTS reports_sender_at ON reports (sender, at);
