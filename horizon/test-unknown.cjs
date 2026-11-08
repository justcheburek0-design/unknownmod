// Real 26.3 protocol clients and console commands against an isolated Horizon world.
// No dependency on a Java server or a graphical client; never touches port 25567.
const fs=require('node:fs'),net=require('node:net'),zlib=require('node:zlib'),crypto=require('node:crypto');
const {execFileSync}=require('node:child_process');
const path=require('node:path');
const root=path.resolve(__dirname,'..'),server=path.join(root,'build/horizon/unknown-test-server');
const supervisor=path.join(root,'build/horizon/runtime/horizon-windows-x64/horizon-server.exe');
const ids=JSON.parse(fs.readFileSync(path.join(root,'build/porting/26.3/packet-ids.json'),'utf8').replace(/^\uFEFF/,''));
const port=25569,bots=new Map(),checks=[];
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function vi(n){const a=[];do{let b=n&127;n>>>=7;if(n)b|=128;a.push(b)}while(n);return Buffer.from(a)}
function str(s){const b=Buffer.from(s);return Buffer.concat([vi(b.length),b])}
function variable(b,o=0){let n=0,s=0,i=o;for(;i<b.length;i++){n|=(b[i]&127)<<s;if(!(b[i]&128))return[n,i+1];s+=7;if(s>35)throw Error('varint')}return null}
class Reader{
 constructor(b){this.b=b;this.o=0} u8(){return this.b[this.o++]} vi(){const [n,o]=variable(this.b,this.o);this.o=o;return n} str(){const n=this.vi(),s=this.b.subarray(this.o,this.o+n).toString();this.o+=n;return s} bytes(){const n=this.vi();this.o+=n}
 nbt(type=this.u8()){
  if(type===0)return null;
  if(type===1)return this.u8();if(type===2){this.o+=2;return null}if(type===3||type===5){this.o+=4;return null}if(type===4||type===6){this.o+=8;return null}
  if(type===8){const n=this.b.readUInt16BE(this.o);this.o+=2;const s=this.b.subarray(this.o,this.o+n).toString();this.o+=n;return s}
  if(type===9){const t=this.u8(),n=this.b.readInt32BE(this.o);this.o+=4;return Array.from({length:n},()=>this.nbt(t))}
  if(type===10){const obj={};for(let t;(t=this.u8())!==0;){const name=this.nbt(8);obj[name]=this.nbt(t)}return obj}
  const unit={7:1,11:4,12:8}[type];if(unit){const n=this.b.readInt32BE(this.o);this.o+=4+n*unit;return null}throw Error('NBT '+type)
 }
}
class Bot{
 constructor(name){
  this.name=name;this.profiles=new Map();this.entities=new Map();this.events=[];this.menus=[];this.health=20;this.joined=false;this.closed=false;
  const uuid=crypto.createHash('md5').update('OfflinePlayer:'+name).digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;this.uuid=uuid.toString('hex');
  this.socket=net.createConnection({host:'127.0.0.1',port});this.state='login';this.compression=null;this.buffer=Buffer.alloc(0);
  this.socket.on('connect',()=>{const p=Buffer.alloc(2);p.writeUInt16BE(port);this.send(0,Buffer.concat([vi(777),str('localhost'),p,vi(2)]));this.send(0,Buffer.concat([str(name),uuid]));});
  this.socket.on('close',()=>this.closed=true);this.socket.on('error',e=>this.error=e.message);
  this.socket.on('data',chunk=>{try{this.receive(chunk)}catch(e){this.error=e.stack;this.socket.destroy()}});bots.set(name,this);
 }
 send(id,data=Buffer.alloc(0)){let p=Buffer.concat([vi(id),data]);if(this.compression!==null)p=p.length>=this.compression?Buffer.concat([vi(p.length),zlib.deflateSync(p)]):Buffer.concat([vi(0),p]);this.socket.write(Buffer.concat([vi(p.length),p]))}
 named(name,data){const id=ids[this.state].server.indexOf(name);if(id<0)throw Error(name);this.send(id,data)}
 receive(chunk){this.buffer=Buffer.concat([this.buffer,chunk]);while(this.buffer.length){const frame=variable(this.buffer);if(!frame||this.buffer.length<frame[0]+frame[1])break;let p=this.buffer.subarray(frame[1],frame[1]+frame[0]);this.buffer=this.buffer.subarray(frame[1]+frame[0]);if(this.compression!==null){const h=variable(p);p=h[0]?zlib.inflateSync(p.subarray(h[1])):p.subarray(h[1])}const h=variable(p),type=ids[this.state].client[h[0]],body=p.subarray(h[1]);this.packet(type,body)}}
 packet(type,body){
  if(this.state==='login'){
   if(type==='CLIENTBOUND_LOGIN_COMPRESSION')this.compression=variable(body)[0];
   if(type==='CLIENTBOUND_LOGIN_FINISHED'){this.named('SERVERBOUND_LOGIN_ACKNOWLEDGED');this.state='configuration';this.named('SERVERBOUND_CUSTOM_PAYLOAD',Buffer.concat([str('minecraft:brand'),str('horizon')]))}
  }else if(this.state==='configuration'){
   if(type==='CLIENTBOUND_PING')this.named('SERVERBOUND_PONG',body);
   if(type==='CLIENTBOUND_KEEP_ALIVE')this.named('SERVERBOUND_KEEP_ALIVE',body);
   if(type==='CLIENTBOUND_SELECT_KNOWN_PACKS')this.named('SERVERBOUND_SELECT_KNOWN_PACKS',vi(0));
   if(type==='CLIENTBOUND_FINISH_CONFIGURATION'){this.named('SERVERBOUND_FINISH_CONFIGURATION');this.state='game'}
  }else{
   if(type==='CLIENTBOUND_LOGIN'){this.entity=body.readInt32BE(0);this.joined=true;}
   if(type==='CLIENTBOUND_ADD_ENTITY'){const r=new Reader(body),id=r.vi();r.o+=16;const kind=r.vi(),position=[0,1,2].map(i=>body.readDoubleBE(r.o+i*8));this.entities.set(id,{id,kind,position});}
   if(type==='CLIENTBOUND_KEEP_ALIVE')this.named('SERVERBOUND_KEEP_ALIVE',body);
   if(type==='CLIENTBOUND_PLAYER_POSITION'){const t=variable(body);this.position=[0,1,2].map(i=>body.readDoubleBE(t[1]+i*8));this.named('SERVERBOUND_ACCEPT_TELEPORTATION',Buffer.concat([vi(t[0]),body.subarray(t[1],t[1]+24),body.subarray(t[1]+48,t[1]+56)]));this.named('SERVERBOUND_PLAYER_LOADED')}
   if(type==='CLIENTBOUND_CHUNK_BATCH_FINISHED'){const rate=Buffer.alloc(4);rate.writeFloatBE(8);this.named('SERVERBOUND_CHUNK_BATCH_RECEIVED',rate)}
   if(type==='CLIENTBOUND_SET_HEALTH')this.health=body.readFloatBE(0);
   if(type==='CLIENTBOUND_GAME_EVENT'&&body[0]===3)this.mode=body.readFloatBE(1);
   if(type==='CLIENTBOUND_SYSTEM_CHAT')this.events.push({type:'chat',text:JSON.stringify(new Reader(body).nbt())});
   if(type==='CLIENTBOUND_PLAYER_INFO_UPDATE')this.profilePacket(body);
   if(type==='CLIENTBOUND_CUSTOM_PAYLOAD'){
    const r=new Reader(body);if(r.str()==='horizon:mod_net'&&r.str()==='unknownmod:menu'){const n=r.vi();this.menus.push(JSON.parse(body.subarray(r.o,r.o+n).toString()));}
   }
   if(type==='CLIENTBOUND_DISCONNECT')this.error='kicked '+body.toString('hex').slice(0,180);
  }
 }
 profilePacket(body){const r=new Reader(body),actions=r.u8(),n=r.vi();for(let i=0;i<n;i++){
  const uuid=body.subarray(r.o,r.o+16).toString('hex');r.o+=16;const p=this.profiles.get(uuid)||{};
  if(actions&1){p.name=r.str();p.properties={};const count=r.vi();for(let j=0;j<count;j++){const key=r.str();p.properties[key]=r.str();if(r.u8())r.str()}}
  if((actions&2)&&r.u8()){r.o+=24;r.bytes();r.bytes()}
  if(actions&4)p.mode=r.vi();if(actions&8)p.listed=!!r.u8();if(actions&16)r.vi();if((actions&32)&&r.u8())r.nbt();if(actions&64)r.vi();if(actions&128)r.u8();this.profiles.set(uuid,p);
 }}
 attack(victim){this.named('SERVERBOUND_ATTACK',vi(victim.entity))}
 respawn(){this.named('SERVERBOUND_CLIENT_COMMAND',vi(0))}
 command(text){this.named('SERVERBOUND_CHAT_COMMAND',str(text))}
 menuRequest(request){const data=Buffer.from(JSON.stringify(request));this.named('SERVERBOUND_CUSTOM_PAYLOAD',Buffer.concat([str('horizon:mod_net'),str('unknownmod:menu'),vi(data.length),data]))}
 disconnect(){this.socket.end()}
}
function cmd(command){return execFileSync(supervisor,['cmd',command,'--dir',server],{encoding:'utf8',windowsHide:true,timeout:15000}).trim()}
function state(){return JSON.parse(fs.readFileSync(path.join(server,'test-world/unknownmod/state.json'),'utf8'))}
function player(name){return Object.values(state().players).find(p=>p.name===name)}
function check(name,condition){if(!condition)throw Error('FAIL '+name);checks.push(name);console.log('PASS '+name)}
function publicStatus(){return new Promise((resolve,reject)=>{
 const socket=net.createConnection({host:'127.0.0.1',port}),timeout=setTimeout(()=>socket.destroy(Error('status timeout')),5000);let buffer=Buffer.alloc(0);
 socket.on('error',reject);socket.on('close',()=>clearTimeout(timeout));
 socket.on('connect',()=>{const p=Buffer.alloc(2);p.writeUInt16BE(port);const handshake=Buffer.concat([vi(0),vi(777),str('localhost'),p,vi(1)]);socket.write(Buffer.concat([vi(handshake.length),handshake,Buffer.from([1,0])]))});
 socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);const frame=variable(buffer);if(!frame||buffer.length<frame[0]+frame[1])return;const reader=new Reader(buffer.subarray(frame[1],frame[1]+frame[0]));reader.vi();try{resolve(JSON.parse(reader.str()))}catch(e){reject(e)}socket.end()});
})}
async function until(fn,label,timeout=15000){const end=Date.now()+timeout;while(Date.now()<end){for(const b of bots.values())if(b.error)throw Error(b.name+': '+b.error);if(fn())return;await sleep(100)}throw Error('Timeout: '+label)}
async function connect(name){const b=new Bot(name);await until(()=>b.joined&&b.position,'join '+name);await sleep(800);return b}
async function setup(a,b,weapon){
 cmd('tp '+a.name+' 0 -60 0');cmd('tp '+b.name+' 1 -60 0');cmd('effect clear '+b.name);cmd('effect give '+b.name+' minecraft:instant_health 1 10 true');
 cmd('item replace entity '+a.name+' weapon.mainhand with '+weapon);await sleep(1300);
}
async function fatal(a,b,weapon){await setup(a,b,weapon);cmd('damage '+b.name+' 19 minecraft:generic');await sleep(650);a.attack(b);await until(()=>b.health<=0,'death '+b.name);await sleep(500)}
async function main(){
 cmd('difficulty easy');
 cmd('gamerule minecraft:spawn_mobs false');cmd('gamerule minecraft:immediate_respawn false');
 cmd('gamerule minecraft:natural_health_regeneration false');
 const a=await connect('TestAttacker');let b=await connect('TestTarget');const observer=await connect('TestObserver');
 cmd('unknown reveal cancel');
 for(const bot of [a,b,observer]){cmd('unknown restore '+bot.name);if(bot.health<=0){bot.respawn();await until(()=>bot.health>0,'initial respawn '+bot.name)}cmd('gamemode survival '+bot.name)}
 await until(()=>observer.profiles.has(b.uuid),'target profile');
 check('initial anonymous profile and no original textures',observer.profiles.get(b.uuid).name==='JustPlayer'&&observer.profiles.get(b.uuid).properties.unknownmod_anonymous==='1'&&!observer.profiles.get(b.uuid).properties.textures);
 const ping=await publicStatus();check('public server status does not leak hidden names',ping.players.online>=3&&(ping.players.sample||[]).every(p=>p.name==='JustPlayer'));
 check('real account names resolve in vanilla commands',cmd('give TestTarget minecraft:apple').includes('TestTarget'));
 cmd('unknown reveal TestTarget 1');await until(()=>observer.profiles.get(b.uuid)?.name==='TestTarget','reveal');check('reveal restores original profile marker',!observer.profiles.get(b.uuid).properties.unknownmod_anonymous);
 const before=Object.values(state().reveals)[0].Paused.remaining;cmd('unknown duration 90');cmd('unknown interval 3');cmd('unknown minplayers 3');cmd('unknown reload');const after=Object.values(state().reveals)[0].Paused.remaining;check('configuration edits preserve active reveal deadline',after<=before&&before-after<10000);
 cmd('unknown hide TestTarget');await until(()=>observer.profiles.get(b.uuid)?.name==='JustPlayer','hide');check('hide restores anonymous appearance',observer.profiles.get(b.uuid).properties.unknownmod_anonymous==='1');
 cmd('unknown roster TestAttacker TestTarget TestObserver NeverJoined');
 const count=observer.events.length;observer.command('players');await until(()=>observer.events.length>count,'players command');check('three-day roster includes inactive participants',observer.events.slice(count).some(e=>e.text.includes('NeverJoined')&&e.text.includes('TestTarget')));
 const messageCount=observer.events.length;observer.command('msg TestTarget secret');await until(()=>observer.events.length>messageCount,'blocked whisper');check('private text communication blocked',observer.events.slice(messageCount).some(e=>e.text.includes('только голосом')));
 cmd('kill TestTarget');await until(()=>b.health<=0,'environment death');check('environmental death does not eliminate',!player(b.name).ghost);b.respawn();await until(()=>b.health>0,'respawn');
 await fatal(a,b,'minecraft:diamond_sword[minecraft:custom_name={text:"WrongName"}]');check('wrongly named PvP death does not eliminate',!player(b.name).ghost);b.respawn();await until(()=>b.health>0,'respawn');
 await fatal(a,b,'minecraft:stick[minecraft:custom_name={text:"TestTarget"}]');check('any correctly named item eliminates',player(b.name).ghost);b.respawn();await until(()=>b.health>0&&b.profiles.get(b.uuid)?.mode===3,'spectator');check('eliminated player respawns as spectator',b.profiles.get(b.uuid).mode===3);
 cmd('unknown restore TestTarget');await until(()=>b.profiles.get(b.uuid)?.mode===0,'restore');check('admin restore returns previous game mode',!player(b.name).ghost);
 await setup(a,b,'minecraft:stick[minecraft:custom_name={text:"TestTarget"}]');a.attack(b);await sleep(700);check('hit accepted before combat logout',b.health<20);b.disconnect();await until(()=>player('TestTarget').ghost,'combat logout elimination');check('named weapon logout eliminates',player('TestTarget').ghost);b=await connect('TestTarget');if(b.health<=0){b.respawn();await until(()=>b.health>0,'reconnect respawn')}await until(()=>b.profiles.get(b.uuid)?.mode===3,'ghost reconnect');check('elimination survives reconnect',b.profiles.get(b.uuid).mode===3);
 cmd('unknown restore TestTarget');await sleep(300);await setup(a,b,'minecraft:stick');a.attack(b);await sleep(600);b.disconnect();await sleep(1000);check('unnamed weapon logout does not eliminate',!player('TestTarget').ghost);b=await connect('TestTarget');check('combat logout records ordinary death',b.health<=0);b.respawn();await until(()=>b.health>0,'respawn after logout');
 cmd('unknown reveal TestTarget 1');await until(()=>observer.profiles.get(b.uuid)?.name==='TestTarget','reveal timer');console.log('Waiting for the real one-minute timer while other checks complete...');
 await sleep(61000);await until(()=>observer.profiles.get(b.uuid)?.properties.unknownmod_anonymous==='1','timer expires');check('real timer returns anonymous profile automatically',observer.profiles.get(b.uuid).name==='JustPlayer');
 cmd('difficulty easy');cmd('team add QAWin');cmd('team join QAWin TestAttacker');cmd('tp TestAttacker 0 -60 0');
 cmd('item replace entity TestAttacker weapon.mainhand with minecraft:diamond_sword');
 cmd('kill @e[type=minecraft:ender_dragon]');await sleep(300);const knownEntities=new Set(a.entities.keys());
 cmd('summon minecraft:ender_dragon 2 -60 0 {NoAI:1b,Health:1.0f,DragonPhase:10}');await sleep(1000);
 const dragon=[...a.entities.values()].find(e=>!knownEntities.has(e.id)&&Math.abs(e.position[0]-2)<1&&e.position[1]===-60);check('real test dragon loaded',dragon&&dragon.id>0);
 a.named('SERVERBOUND_ATTACK',vi(dragon.id+1));
 await until(()=>JSON.parse(fs.readFileSync(path.join(server,'test-world/unknownmod/winner.json'),'utf8'))!==null,'dragon victory');
 check('dragon kill awards the killers team',JSON.parse(fs.readFileSync(path.join(server,'test-world/unknownmod/winner.json'),'utf8'))==='QAWin');
 const result={passed:checks.length,checks,finished:new Date().toISOString()};fs.writeFileSync(path.join(root,'build/horizon/unknown-test-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}
module.exports={Bot,connect,cmd,state,player,until,sleep,bots};
if(require.main===module)main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of bots.values())b.socket.destroy()});
