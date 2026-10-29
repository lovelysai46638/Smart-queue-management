create extension if not exists pgcrypto;

create table if not exists public.profiles (
 id uuid primary key references auth.users(id) on delete cascade,
 full_name text not null default 'Student',
 student_id text unique,
 phone text,
 role text not null default 'student' check (role in ('student','admin')),
 created_at timestamptz not null default now()
);
create table if not exists public.categories (
 id uuid primary key default gen_random_uuid(),
 name text not null unique,
 description text,
 created_at timestamptz not null default now()
);
create table if not exists public.menu_items (
 id uuid primary key default gen_random_uuid(),
 name text not null,
 description text,
 category_id uuid not null references public.categories(id) on delete restrict,
 price numeric(10,2) not null check(price > 0),
 image_url text,
 is_available boolean not null default true,
 is_vegetarian boolean,
 preparation_time_minutes integer not null default 10 check(preparation_time_minutes between 1 and 240),
 is_featured boolean not null default false,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create table if not exists public.orders (
 id uuid primary key default gen_random_uuid(),
 token_number text not null unique,
 student_id uuid not null references public.profiles(id) on delete restrict,
 status text not null default 'Pending' check(status in ('Pending','Accepted','Preparing','Ready for Pickup','Completed','Cancelled')),
 total_amount numeric(10,2) not null check(total_amount >= 0),
 payment_method text not null check(payment_method in ('Pay at Counter','Online Payment')),
 payment_status text not null default 'unpaid' check(payment_status in ('unpaid','paid','refunded','failed')),
 pickup_slot timestamptz,
 estimated_ready_at timestamptz,
 cancellation_reason text,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create table if not exists public.order_items (
 id uuid primary key default gen_random_uuid(),
 order_id uuid not null references public.orders(id) on delete cascade,
 menu_item_id uuid references public.menu_items(id) on delete set null,
 item_name text not null,
 unit_price numeric(10,2) not null check(unit_price >= 0),
 quantity integer not null check(quantity between 1 and 30),
 subtotal numeric(10,2) not null check(subtotal >= 0),
 created_at timestamptz not null default now()
);
create table if not exists public.notifications (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references public.profiles(id) on delete cascade,
 order_id uuid references public.orders(id) on delete set null,
 title text not null,
 message text not null,
 is_read boolean not null default false,
 created_at timestamptz not null default now()
);
create table if not exists public.order_status_history (
 id uuid primary key default gen_random_uuid(),
 order_id uuid not null references public.orders(id) on delete cascade,
 old_status text,
 new_status text not null,
 changed_by uuid references public.profiles(id) on delete set null,
 created_at timestamptz not null default now()
);

create index if not exists idx_menu_category on public.menu_items(category_id);
create index if not exists idx_menu_available on public.menu_items(is_available);
create index if not exists idx_orders_student_created on public.orders(student_id,created_at desc);
create index if not exists idx_orders_status_created on public.orders(status,created_at);
create index if not exists idx_order_items_order on public.order_items(order_id);
create index if not exists idx_notifications_user on public.notifications(user_id,created_at desc);
create index if not exists idx_history_order on public.order_status_history(order_id,created_at);

create or replace function public.set_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end; $$;
drop trigger if exists menu_items_updated_at on public.menu_items;
create trigger menu_items_updated_at before update on public.menu_items for each row execute function public.set_updated_at();
drop trigger if exists orders_updated_at on public.orders;
create trigger orders_updated_at before update on public.orders for each row execute function public.set_updated_at();

create or replace function public.handle_new_user() returns trigger language plpgsql security definer set search_path=public as $$
begin
 insert into public.profiles(id,full_name,student_id,role)
 values(new.id, coalesce(new.raw_user_meta_data->>'full_name','Student'),
        nullif(new.raw_user_meta_data->>'student_id',''), 'student')
 on conflict(id) do nothing;
 return new;
end; $$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users for each row execute procedure public.handle_new_user();

-- Role changes are not allowed through browser profile updates.
create or replace function public.prevent_profile_role_change() returns trigger language plpgsql as $$
begin
 if new.role is distinct from old.role then
   if current_user not in ('postgres','supabase_admin','service_role') then
     raise exception 'Profile role cannot be changed by the client';
   end if;
 end if;
 return new;
end; $$;
drop trigger if exists profile_role_guard on public.profiles;
create trigger profile_role_guard before update on public.profiles for each row execute function public.prevent_profile_role_change();

alter table public.profiles enable row level security;
alter table public.categories enable row level security;
alter table public.menu_items enable row level security;
alter table public.orders enable row level security;
alter table public.order_items enable row level security;
alter table public.notifications enable row level security;
alter table public.order_status_history enable row level security;

drop policy if exists "profiles read own" on public.profiles;
create policy "profiles read own" on public.profiles for select to authenticated using(id=auth.uid());
drop policy if exists "profiles update own permitted fields" on public.profiles;
create policy "profiles update own permitted fields" on public.profiles for update to authenticated using(id=auth.uid()) with check(id=auth.uid() and role='student');
drop policy if exists "categories visible" on public.categories;
create policy "categories visible" on public.categories for select to anon,authenticated using(true);
drop policy if exists "menu visible" on public.menu_items;
create policy "menu visible" on public.menu_items for select to anon,authenticated using(true);
drop policy if exists "orders own read" on public.orders;
create policy "orders own read" on public.orders for select to authenticated using(student_id=auth.uid());
drop policy if exists "order items own read" on public.order_items;
create policy "order items own read" on public.order_items for select to authenticated using(exists(select 1 from public.orders o where o.id=order_id and o.student_id=auth.uid()));
drop policy if exists "notifications own read" on public.notifications;
create policy "notifications own read" on public.notifications for select to authenticated using(user_id=auth.uid());
drop policy if exists "notifications own read update" on public.notifications;
create policy "notifications own read update" on public.notifications for update to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
drop policy if exists "history own read" on public.order_status_history;
create policy "history own read" on public.order_status_history for select to authenticated using(exists(select 1 from public.orders o where o.id=order_id and o.student_id=auth.uid()));

-- Browser-side writes to orders/order_items/history are intentionally not granted.
-- Sensitive writes go through the authenticated Spring Boot API.
insert into public.categories(name,description) values
 ('Breakfast','Fresh morning favourites'),('Meals','Filling campus meals'),('Snacks','Quick bites between classes'),
 ('Fast Food','Campus classics'),('Beverages','Cold and hot drinks'),('Desserts','A sweet finish')
on conflict(name) do nothing;

-- Demo menu seed; images are public Unsplash image URLs, replace in admin menu if desired.
insert into public.menu_items(name,description,category_id,price,image_url,is_available,is_vegetarian,preparation_time_minutes,is_featured)
select v.name,v.description,c.id,v.price,v.image_url,true,v.veg,v.prep,v.featured
from (values
 ('Masala Dosa','Crispy dosa with potato masala and chutneys','Breakfast',55.00,'https://images.unsplash.com/photo-1668236543090-82eba5ee5976?w=800',true,12,true),
 ('Idli Sambar','Soft idlis served with sambar','Breakfast',35.00,'https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=800',true,8,false),
 ('Veg Meals','Rice, seasonal curry, dal and sides','Meals',90.00,'https://images.unsplash.com/photo-1546833999-b9f581a1996d?w=800',true,15,true),
 ('Paneer Wrap','Spiced paneer and salad in a warm wrap','Fast Food',85.00,'https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=800',true,10,false),
 ('Veg Puff','Flaky pastry with savoury vegetable filling','Snacks',25.00,'https://images.unsplash.com/photo-1601050690597-df0568f70950?w=800',true,5,false),
 ('Cold Coffee','Chilled creamy coffee','Beverages',50.00,'https://images.unsplash.com/photo-1461023058943-07fcbe16d735?w=800',true,4,true),
 ('Brownie','Chocolate brownie square','Desserts',45.00,'https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=800',true,3,false)
) as v(name,description,category,price,image_url,veg,prep,featured)
join public.categories c on c.name=v.category
where not exists(select 1 from public.menu_items m where m.name=v.name);

-- Realtime publications are optional in some project setups; ignore if already present.
do $$ begin
 alter publication supabase_realtime add table public.orders;
exception when duplicate_object then null; when undefined_object then null; end $$;
do $$ begin
 alter publication supabase_realtime add table public.menu_items;
exception when duplicate_object then null; when undefined_object then null; end $$;
do $$ begin
 alter publication supabase_realtime add table public.notifications;
exception when duplicate_object then null; when undefined_object then null; end $$;

-- Storage bucket is public for viewing food photos; writes are admin-only.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('menu-images','menu-images',true,5242880,array['image/jpeg','image/png','image/webp'])
on conflict(id) do nothing;
drop policy if exists "public can view menu images" on storage.objects;
create policy "public can view menu images" on storage.objects for select using(bucket_id='menu-images');
drop policy if exists "admins upload menu images" on storage.objects;
create policy "admins upload menu images" on storage.objects for insert to authenticated
with check(bucket_id='menu-images' and exists(select 1 from public.profiles p where p.id=auth.uid() and p.role='admin'));
drop policy if exists "admins update menu images" on storage.objects;
create policy "admins update menu images" on storage.objects for update to authenticated
using(bucket_id='menu-images' and exists(select 1 from public.profiles p where p.id=auth.uid() and p.role='admin'))
with check(bucket_id='menu-images' and exists(select 1 from public.profiles p where p.id=auth.uid() and p.role='admin'));
drop policy if exists "admins delete menu images" on storage.objects;
create policy "admins delete menu images" on storage.objects for delete to authenticated
using(bucket_id='menu-images' and exists(select 1 from public.profiles p where p.id=auth.uid() and p.role='admin'));
