# Safely authorize the first canteen admin

There is intentionally no public admin signup. First create an account using Supabase Auth (you can use the student signup form once, or create the user in Supabase Authentication). Then find that user's UUID in **Authentication → Users**.

Run this in the Supabase SQL Editor as the project owner, replacing the UUID and name. Never expose the database owner credential or service-role key in frontend code.

```sql
insert into public.profiles (id, full_name, student_id, role)
values ('PASTE-AUTH-USER-UUID-HERE', 'Canteen Manager', null, 'admin')
on conflict (id) do update
set full_name = excluded.full_name,
    role = 'admin';
```

The profile trigger normally creates a student profile automatically, so the `ON CONFLICT` clause promotes only the exact account you selected as project owner. Keep this SQL out of any public endpoint.
