import {createClient} from '@supabase/supabase-js';
export const supabase=createClient(import.meta.env.VITE_SUPABASE_URL||'',import.meta.env.VITE_SUPABASE_ANON_KEY||'');
const API=import.meta.env.VITE_API_URL||'http://localhost:8080/api';
export async function api(path,options={}) {
 const {data:{session}}=await supabase.auth.getSession();
 const headers={'Content-Type':'application/json',...(session?.access_token?{Authorization:`Bearer ${session.access_token}`}:{ }),...(options.headers||{})};
 const res=await fetch(`${API}${path}`,{...options,headers});
 const text=await res.text(); let body={}; try{body=text?JSON.parse(text):{};}catch{body={message:text};}
 if(!res.ok) throw new Error(body.message||body.error||`Request failed (${res.status})`);
 return body;
}
export const money=n=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(Number(n||0));
export const statusClass=s=>String(s||'pending').toLowerCase().replaceAll(' ','-');
