package edu.campus.canteen;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
 private final JdbcTemplate db;
 public ApiController(JdbcTemplate db) { this.db=db; }

 @GetMapping("/health") Map<String,Object> health(){ return Map.of("status","ok","service","smart-canteen-api"); }
 private UUID uid(Authentication a) {
   try { return UUID.fromString(a.getName()); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid user token"); }
 }
 private String role(UUID id) {
   List<String> roles=db.query("select role from public.profiles where id=?", (r,n)->r.getString(1),id);
   if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Profile not found. Complete signup first.");
   return roles.get(0);
 }
 private void admin(UUID id){ if(!"admin".equals(role(id))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin access required"); }

 @GetMapping("/me") Map<String,Object> me(Authentication a) {
   UUID id=uid(a); var rows=db.queryForList("select id,full_name,student_id,phone,role,created_at from public.profiles where id=?",id);
   if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Profile not found");
   return rows.get(0);
 }
 @PutMapping("/me") Map<String,Object> updateMe(Authentication a,@RequestBody ProfileUpdate p) {
   UUID id=uid(a); if(p.fullName()==null||p.fullName().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Name is required");
   db.update("update public.profiles set full_name=?, student_id=?, phone=? where id=?",p.fullName().trim(),p.studentId(),p.phone(),id);
   return me(a);
 }
 public record ProfileUpdate(String fullName,String studentId,String phone){}
 @GetMapping("/categories") List<Map<String,Object>> categories(){ return db.queryForList("select id,name,description from public.categories order by name"); }
 @GetMapping("/menu") List<Map<String,Object>> menu(@RequestParam(required=false) String q,@RequestParam(required=false) String category,@RequestParam(required=false) Boolean available) {
   return db.queryForList("""
    select m.id,m.name,m.description,m.category_id,c.name category,m.price,m.image_url,m.is_available,m.is_vegetarian,
           m.preparation_time_minutes,m.is_featured
    from public.menu_items m join public.categories c on c.id=m.category_id
    where (? is null or m.name ilike '%'||?||'%' or m.description ilike '%'||?||'%')
      and (? is null or c.name=?) and (? is null or m.is_available=?)
    order by m.is_featured desc, m.name
    """,q,q,q,category,category,available,available);
 }
 public record CartLine(@NotNull UUID menuItemId,@Min(1) @Max(30) int quantity){}
 public record PlaceOrder(@NotEmpty List<@Valid CartLine> items,@NotBlank String paymentMethod,String pickupSlot){}
 @PostMapping("/orders") @Transactional
 Map<String,Object> placeOrder(Authentication auth,@Valid @RequestBody PlaceOrder req) {
   UUID student=uid(auth); role(student);
   if(!"PAY_AT_COUNTER".equals(req.paymentMethod())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only Pay at Counter is currently supported");
   if(req.items().size()>30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Too many different items");
   Map<UUID,Integer> quantities=new LinkedHashMap<>();
   for(CartLine line:req.items()) quantities.merge(line.menuItemId(),line.quantity(),Integer::sum);
   BigDecimal total=BigDecimal.ZERO; List<Map<String,Object>> lines=new ArrayList<>();
   for(var entry:quantities.entrySet()){
     var found=db.query("select id,name,price,is_available,preparation_time_minutes from public.menu_items where id=? for share",
       (r,n)->new Object[]{r.getString("name"),r.getBigDecimal("price"),r.getBoolean("is_available"),r.getInt("preparation_time_minutes")},entry.getKey());
     if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A menu item no longer exists");
     Object[] item=found.get(0); if(!(Boolean)item[2]) throw new ResponseStatusException(HttpStatus.CONFLICT,item[0]+" is sold out");
     BigDecimal unit=(BigDecimal)item[1]; BigDecimal subtotal=unit.multiply(BigDecimal.valueOf(entry.getValue())); total=total.add(subtotal);
     lines.add(Map.of("id",entry.getKey(),"name",item[0],"price",unit,"qty",entry.getValue(),"subtotal",subtotal,"prep",(Integer)item[3]));
   }
   int prep=lines.stream().mapToInt(x->(Integer)x.get("prep")).max().orElse(10);
   OffsetDateTime pickup=null;
   if(req.pickupSlot()!=null&&!req.pickupSlot().isBlank()) try { pickup=OffsetDateTime.parse(req.pickupSlot()); } catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pickup time"); }
   String token="SC-"+LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+"-"+UUID.randomUUID().toString().substring(0,6).toUpperCase(Locale.ROOT);
   UUID orderId=UUID.randomUUID();
   db.update("""
     insert into public.orders(id,token_number,student_id,status,total_amount,payment_method,payment_status,pickup_slot,estimated_ready_at)
     values(?,?,?,'Pending',?,'Pay at Counter','unpaid',?,now() + (? * interval '1 minute'))
     """,orderId,token,student,total,pickup,prep);
   for(var line:lines) db.update("""
     insert into public.order_items(order_id,menu_item_id,item_name,unit_price,quantity,subtotal)
     values(?,?,?,?,?,?)
     """,orderId,line.get("id"),line.get("name"),line.get("price"),line.get("qty"),line.get("subtotal"));
   db.update("insert into public.order_status_history(order_id,old_status,new_status,changed_by) values(?,null,'Pending',?)",orderId,student);
   db.update("insert into public.notifications(user_id,order_id,title,message) values(?,?,?,?)",student,orderId,"Order placed","Your token "+token+" is confirmed.");
   return Map.of("id",orderId,"tokenNumber",token,"status","Pending","totalAmount",total,"paymentMethod","Pay at Counter","estimatedReadyAt",OffsetDateTime.now().plusMinutes(prep),"items",lines);
 }
 @GetMapping("/orders/mine") List<Map<String,Object>> myOrders(Authentication a){ return db.queryForList("select id,token_number,status,total_amount,payment_method,payment_status,pickup_slot,estimated_ready_at,cancellation_reason,created_at,updated_at from public.orders where student_id=? order by created_at desc",uid(a)); }
 @GetMapping("/orders/{id}") Map<String,Object> order(Authentication a,@PathVariable UUID id) {
   UUID user=uid(a); boolean isAdmin="admin".equals(role(user));
   var rows=db.queryForList("select o.*,p.full_name student_name from public.orders o join public.profiles p on p.id=o.student_id where o.id=? and (? or o.student_id=?)",id,isAdmin,user);
   if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
   var result=new HashMap<String,Object>(rows.get(0));
   result.put("items",db.queryForList("select item_name,unit_price,quantity,subtotal from public.order_items where order_id=?",id));
   result.put("history",db.queryForList("select old_status,new_status,created_at from public.order_status_history where order_id=? order by created_at",id));
   return result;
 }
 @PostMapping("/orders/{id}/cancel") @Transactional Map<String,Object> cancel(Authentication a,@PathVariable UUID id,@RequestBody(required=false) Map<String,String> body){
   UUID user=uid(a);
   var rows=db.queryForList("select status from public.orders where id=? and student_id=? for update",id,user);
   if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
   String old=(String)rows.get(0).get("status");
   if(!List.of("Pending","Accepted").contains(old)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This order can no longer be cancelled");
   String reason=body==null?"Cancelled by student":body.getOrDefault("reason","Cancelled by student");
   db.update("update public.orders set status='Cancelled',cancellation_reason=?,updated_at=now() where id=?",reason,id);
   db.update("insert into public.order_status_history(order_id,old_status,new_status,changed_by) values(?,?,'Cancelled',?)",id,old,user);
   db.update("insert into public.notifications(user_id,order_id,title,message) values(?,?,?,?)",user,id,"Order cancelled",reason);
   return Map.of("id",id,"status","Cancelled");
 }
 @GetMapping("/notifications") List<Map<String,Object>> notifications(Authentication a){return db.queryForList("select id,order_id,title,message,is_read,created_at from public.notifications where user_id=? order by created_at desc limit 100",uid(a));}
 @PatchMapping("/notifications/{id}/read") Map<String,Object> readNotification(Authentication a,@PathVariable UUID id){
   int n=db.update("update public.notifications set is_read=true where id=? and user_id=?",id,uid(a));
   if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found"); return Map.of("success",true);
 }

 @GetMapping("/admin/dashboard") Map<String,Object> dashboard(Authentication a){
   admin(uid(a));
   var counts=db.queryForList("""
    select count(*) filter(where created_at::date=current_date) total_today,
    count(*) filter(where status='Pending') pending,
    count(*) filter(where status='Accepted') accepted,
    count(*) filter(where status='Preparing') preparing,
    count(*) filter(where status='Ready for Pickup') ready,
    count(*) filter(where status='Completed') completed,
    count(*) filter(where status='Cancelled') cancelled,
    coalesce(sum(total_amount) filter(where status='Completed' and created_at::date=current_date),0) sales_today
    from public.orders
    """);
   return counts.get(0);
 }
 @GetMapping("/admin/orders") List<Map<String,Object>> adminOrders(Authentication a,@RequestParam(required=false) String status,@RequestParam(required=false) String q){
   admin(uid(a));
   return db.queryForList("""
    select o.id,o.token_number,o.status,o.total_amount,o.payment_method,o.payment_status,o.pickup_slot,o.estimated_ready_at,o.created_at,o.updated_at,
    p.full_name student_name,p.student_id,p.phone
    from public.orders o join public.profiles p on p.id=o.student_id
    where (? is null or o.status=?) and (? is null or o.token_number ilike '%'||?||'%' or p.full_name ilike '%'||?||'%' or p.student_id ilike '%'||?||'%')
    order by case o.status when 'Pending' then 0 when 'Accepted' then 1 when 'Preparing' then 2 when 'Ready for Pickup' then 3 else 4 end, o.created_at
    """,status,status,q,q,q,q);
 }
 public record StatusUpdate(@NotBlank String status,String reason){}
 @PatchMapping("/admin/orders/{id}/status") @Transactional Map<String,Object> updateStatus(Authentication a,@PathVariable UUID id,@Valid @RequestBody StatusUpdate req){
   UUID actor=uid(a); admin(actor);
   Set<String> valid=Set.of("Accepted","Preparing","Ready for Pickup","Completed","Cancelled");
   if(!valid.contains(req.status())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid status");
   var rows=db.queryForList("select status,student_id from public.orders where id=? for update",id);
   if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
   String old=(String)rows.get(0).get("status"); UUID student=(UUID)rows.get(0).get("student_id");
   Map<String,Set<String>> allowed=Map.of("Pending",Set.of("Accepted","Cancelled"),"Accepted",Set.of("Preparing","Cancelled"),"Preparing",Set.of("Ready for Pickup","Cancelled"),"Ready for Pickup",Set.of("Completed"),"Completed",Set.of(),"Cancelled",Set.of());
   if(!allowed.getOrDefault(old,Set.of()).contains(req.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Transition not allowed: "+old+" → "+req.status());
   db.update("update public.orders set status=?,cancellation_reason=?,updated_at=now() where id=?",req.status(), "Cancelled".equals(req.status())?req.reason():null,id);
   db.update("insert into public.order_status_history(order_id,old_status,new_status,changed_by) values(?,?,?,?)",id,old,req.status(),actor);
   String message=switch(req.status()){case "Accepted"->"The canteen accepted your order.";case "Preparing"->"Your food is now being prepared.";case "Ready for Pickup"->"Your order is ready. Please collect it at the counter.";case "Completed"->"Your order has been completed. Thank you!";default->"Your order was cancelled. "+Objects.toString(req.reason(),"");};
   db.update("insert into public.notifications(user_id,order_id,title,message) values(?,?,?,?)",student,id,"Order "+req.status(),message);
   return Map.of("id",id,"status",req.status());
 }
 public record MenuInput(@NotBlank String name,String description,@NotNull UUID categoryId,@NotNull @DecimalMin("0.01") BigDecimal price,String imageUrl,boolean isAvailable,Boolean isVegetarian,@Min(1) @Max(240) int preparationTimeMinutes,boolean isFeatured){}
 @PostMapping("/admin/menu") Map<String,Object> addMenu(Authentication a,@Valid @RequestBody MenuInput m){
   admin(uid(a)); UUID id=UUID.randomUUID();
   db.update("insert into public.menu_items(id,name,description,category_id,price,image_url,is_available,is_vegetarian,preparation_time_minutes,is_featured) values(?,?,?,?,?,?,?,?,?,?)",id,m.name().trim(),m.description(),m.categoryId(),m.price(),m.imageUrl(),m.isAvailable(),m.isVegetarian(),m.preparationTimeMinutes(),m.isFeatured());
   return Map.of("id",id,"success",true);
 }
 @PutMapping("/admin/menu/{id}") Map<String,Object> editMenu(Authentication a,@PathVariable UUID id,@Valid @RequestBody MenuInput m){
   admin(uid(a)); int n=db.update("update public.menu_items set name=?,description=?,category_id=?,price=?,image_url=?,is_available=?,is_vegetarian=?,preparation_time_minutes=?,is_featured=?,updated_at=now() where id=?",m.name().trim(),m.description(),m.categoryId(),m.price(),m.imageUrl(),m.isAvailable(),m.isVegetarian(),m.preparationTimeMinutes(),m.isFeatured(),id);
   if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Menu item not found"); return Map.of("success",true);
 }
 @DeleteMapping("/admin/menu/{id}") Map<String,Object> deleteMenu(Authentication a,@PathVariable UUID id){
   admin(uid(a)); int n=db.update("delete from public.menu_items where id=?",id);
   if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Menu item not found"); return Map.of("success",true);
 }
 @GetMapping("/admin/reports") Map<String,Object> reports(Authentication a,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){
   admin(uid(a)); LocalDate start=from==null?LocalDate.now().withDayOfMonth(1):from; LocalDate end=to==null?LocalDate.now():to;
   var totals=db.queryForList("""
    select count(*) order_count, count(*) filter(where status='Cancelled') cancellations,
    coalesce(sum(total_amount) filter(where status='Completed'),0) completed_sales,
    coalesce(sum(total_amount) filter(where status not in ('Cancelled')),0) gross_non_cancelled_value
    from public.orders where created_at >= ? and created_at < (?::date + interval '1 day')
    """,start,end);
   var popular=db.queryForList("""
    select oi.item_name,sum(oi.quantity) quantity from public.order_items oi join public.orders o on o.id=oi.order_id
    where o.created_at >= ? and o.created_at < (?::date + interval '1 day') and o.status='Completed'
    group by oi.item_name order by quantity desc limit 8
    """,start,end);
   var statuses=db.queryForList("select status,count(*) count from public.orders where created_at >= ? and created_at < (?::date + interval '1 day') group by status order by status",start,end);
   return Map.of("from",start,"to",end,"totals",totals.get(0),"popularItems",popular,"statuses",statuses);
 }
}
