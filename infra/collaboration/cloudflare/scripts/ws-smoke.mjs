import WebSocket from "ws";
const url=process.env.NORFOLD_COLLAB_WS; const token=process.env.NORFOLD_COLLAB_TOKEN;
if(!url||!token){console.error("Set NORFOLD_COLLAB_WS and NORFOLD_COLLAB_TOKEN.");process.exit(2)}
function connect(name){return new Promise((resolve,reject)=>{const ws=new WebSocket(url,{headers:{Authorization:`Bearer ${token}`}});const timer=setTimeout(()=>{try{ws.terminate()}catch{};reject(new Error(`${name}: timeout`))},10000);ws.once('open',()=>{clearTimeout(timer);resolve(ws)});ws.once('error',e=>{clearTimeout(timer);reject(new Error(`${name}: ${e.message}`))})})}
const a=await connect('A');const b=await connect('B');
const received=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('B did not receive smoke.preview')),10000);b.on('message',raw=>{const data=JSON.parse(raw.toString());if(data.type==='smoke.preview'){clearTimeout(timer);resolve(data)}})});
a.send(JSON.stringify({type:'smoke.preview',seq:1,targetId:'object-1',payload:{x:12,y:34},clientSentAt:Date.now()}));
console.log(JSON.stringify({ok:true,received:await received},null,2));a.close();b.close();
