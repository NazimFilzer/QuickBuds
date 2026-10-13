# Report Worker

The problem report's backend: a Cloudflare Worker on the free plan, with a D1 database. The apps post
each report to `/report`. The Worker stores only reports with a known model, a description and, when a log is on,
frames from the buds. A model test ("Test" category) always has the log. A crash report skips these checks. One sender can post 5 reports an hour.

## Deploy (once)

From this folder, with a free Cloudflare account:

```bash
npx wrangler login
npx wrangler d1 create quickbuds-reports        # copy the database_id into wrangler.toml
npx wrangler d1 execute quickbuds-reports --remote --file schema.sql
npx wrangler secret put READ_KEY                # a long random string: the key to the page
npx wrangler secret put SALT                    # another random string
npx wrangler deploy
```

`openssl rand -hex 24` makes a random string. A change to `src/index.js` or `models.json` needs only
`npx wrangler deploy`.

## Read the reports

- In a browser: `https://quickbuds-report.<subdomain>.workers.dev/?key=<READ_KEY>`
- For an agent: add `&format=json`.
- Only newer reports: `&after=<id>`. More reports: `&limit=100`.

The key is the only access control. Keep the link private.

## Mark a report

Each report has a `status`. The page shows only `new` and `working` reports.

- `new`: not read yet.
- `working`: a fix is in progress.
- `done`: fixed, or answered.
- `unsupported`: the buds are not a supported model.
- `no_log`: the log is missing or shows nothing.
- `no_description`: the description is empty or unclear.
- `needs_btsnoop`: only a btsnoop capture can show the cause.

```bash
npx wrangler d1 execute quickbuds-reports --remote --command "UPDATE reports SET status = 'done', note = 'fixed in 4.13.0' WHERE id = 7"
```

## Delete old reports

```bash
npx wrangler d1 execute quickbuds-reports --remote --command "DELETE FROM reports WHERE at < '2026-01-01'"
```
