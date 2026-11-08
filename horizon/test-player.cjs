const fs=require('node:fs'),net=require('node:net'),zlib=require('node:zlib'),crypto=require('node:crypto'),readline=require('node:readline');
const ids=JSON.parse(fs.readFileSync(__dirname+'/../build/porting/26.3/packet-ids.json','utf8').replace(/^\uFEFF/,''));const bots=new Map();
function vi(n){const a=[];do{let b=n&127;n>>>=7;if(n)b|=128;a.push(b)}while(n);return Buffer.from(a)}
function str(s){const b=Buffer.from(s);return Buffer.concat([vi(b.length),b])}
function read(b,o=0){let n=0,s=0,i=o;for(;i<b.length;i++){n|=(b[i]&127)<<s;if(!(b[i]&128))return[n,i+1];s+=7;if(s>35)throw Error('varint')}return null}
function log(event,data){console.log(JSON.stringify({event,...data}))}
function connect(name){
 const uuid=crypto.createHash('md5').update('OfflinePlayer:'+name).digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;
 const hex=uuid.toString('hex'),uuidText=hex.slice(0,8)+'-'+hex.slice(8,12)+'-'+hex.slice(12,16)+'-'+hex.slice(16,20)+'-'+hex.slice(20);
 const c={socket:net.createConnection({host:'127.0.0.1',port:25567}),state:'login',compression:null,buffer:Buffer.alloc(0),uuid:uuidText};
 bots.set(name,c);
 c.send=(id,data=Buffer.alloc(0))=>{let p=Buffer.concat([vi(id),data]);if(c.compression!==null)p=p.length>=c.compression?Buffer.concat([vi(p.length),zlib.deflateSync(p)]):Buffer.concat([vi(0),p]);c.socket.write(Buffer.concat([vi(p.length),p]))};
 c.sendNamed=(id,data)=>{const n=ids[c.state].server.indexOf(id);if(n<0)throw Error('Unknown '+id);c.send(n,data)};
 c.socket.on('connect',()=>{const port=Buffer.alloc(2);port.writeUInt16BE(25567);c.send(0,Buffer.concat([vi(777),str('localhost'),port,vi(2)]));c.send(0,Buffer.concat([str(name),uuid]));log('connecting',{name,uuid:uuidText})});
 c.socket.on('error',e=>log('error',{name,error:e.message}));
 c.socket.on('close',()=>{if(bots.get(name)===c)bots.delete(name);log('closed',{name})});
 c.socket.on('data',chunk=>{try{
 c.buffer=Buffer.concat([c.buffer,chunk]);while(c.buffer.length){const frame=read(c.buffer);if(!frame||c.buffer.length<frame[1]+frame[0])break;
 let p=c.buffer.subarray(frame[1],frame[1]+frame[0]);c.buffer=c.buffer.subarray(frame[1]+frame[0]);
 if(c.compression!==null){const h=read(p);p=h[0]?zlib.inflateSync(p.subarray(h[1])):p.subarray(h[1])}
 const h=read(p),type=ids[c.state].client[h[0]],body=p.subarray(h[1]);
 if(c.state==='login'){
   if(type==='CLIENTBOUND_LOGIN_COMPRESSION')c.compression=read(body)[0];
   if(type==='CLIENTBOUND_LOGIN_FINISHED'){const len=read(body,16);log('authenticated',{name,publicName:body.subarray(len[1],len[1]+len[0]).toString()});c.sendNamed('SERVERBOUND_LOGIN_ACKNOWLEDGED');c.state='configuration'}
 }else if(c.state==='configuration'){
   if(type==='CLIENTBOUND_PING')c.sendNamed('SERVERBOUND_PONG',body);
   if(type==='CLIENTBOUND_KEEP_ALIVE')c.sendNamed('SERVERBOUND_KEEP_ALIVE',body);
   if(type==='CLIENTBOUND_SELECT_KNOWN_PACKS')c.sendNamed('SERVERBOUND_SELECT_KNOWN_PACKS',vi(0));
   if(type==='CLIENTBOUND_FINISH_CONFIGURATION'){c.sendNamed('SERVERBOUND_FINISH_CONFIGURATION');c.state='game'}
 }else{
   if(type==='CLIENTBOUND_LOGIN')log('joined',{name,uuid:uuidText});
   if(type==='CLIENTBOUND_KEEP_ALIVE')c.sendNamed('SERVERBOUND_KEEP_ALIVE',body);
   if(type==='CLIENTBOUND_PLAYER_POSITION'){const t=read(body);c.sendNamed('SERVERBOUND_ACCEPT_TELEPORTATION',Buffer.concat([vi(t[0]),body.subarray(t[1],t[1]+24),body.subarray(t[1]+48,t[1]+56)]));if(ids.game.server.includes('SERVERBOUND_PLAYER_LOADED'))c.sendNamed('SERVERBOUND_PLAYER_LOADED')}
   if(type==='CLIENTBOUND_CHUNK_BATCH_FINISHED'){const rate=Buffer.alloc(4);rate.writeFloatBE(8);c.sendNamed('SERVERBOUND_CHUNK_BATCH_RECEIVED',rate)}
   if(type==='CLIENTBOUND_SET_HEALTH'){const health=body.readFloatBE(0);log('health',{name,health});if(health<=0)setTimeout(()=>{if(!c.socket.destroyed)c.sendNamed('SERVERBOUND_CLIENT_COMMAND',vi(0))},500);}
   if(type==='CLIENTBOUND_GAME_EVENT')log('gameEvent',{name,reason:body[0],value:body.readFloatBE(1)});
   if(type==='CLIENTBOUND_RESPAWN')log('respawn',{name});
   if(type==='CLIENTBOUND_COMMAND_SUGGESTIONS'){let o=0;const fields=[];for(let i=0;i<4;i++){const v=read(body,o);fields.push(v[0]);o=v[1]}const names=[];for(let i=0;i<fields[3];i++){const n=read(body,o);o=n[1];names.push(body.subarray(o,o+n[0]).toString());o+=n[0];if(body[o++])break}log('suggestions',{name,id:fields[0],names})}
 }
 if(type?.includes('DISCONNECT'))log('kick',{name,type,reason:body.toString('hex').slice(0,180)});
 }
 }catch(e){log('parseError',{name,error:e.stack});c.socket.destroy()}
 });
}
readline.createInterface({input:process.stdin}).on('line',line=>{try{const cmd=JSON.parse(line),c=bots.get(cmd.name);if(cmd.action==='connect')connect(cmd.name);if(cmd.action==='disconnect')c?.socket.end();if(cmd.action==='suggest')c?.sendNamed('SERVERBOUND_COMMAND_SUGGESTION',Buffer.concat([vi(cmd.id||1),str(cmd.text)]));if(cmd.action==='respawn')c?.sendNamed('SERVERBOUND_CLIENT_COMMAND',vi(0));if(cmd.action==='quit'){for(const c of bots.values())c.socket.end();setTimeout(()=>process.exit(),100)}}catch(e){log('commandError',{error:e.message})}});
for(const name of (process.argv.length>2?process.argv.slice(2):['TestPlayer26']))connect(name);
