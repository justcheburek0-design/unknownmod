// Two real Minecraft connections deliver voice secrets directly to the Rust proof
// over stdin. Secrets are never printed or written to disk.
const fs = require('node:fs'), net = require('node:net'), zlib = require('node:zlib');
const crypto = require('node:crypto'), cp = require('node:child_process'), path = require('node:path');
const ids = JSON.parse(fs.readFileSync(path.join(__dirname, '../build/porting/26.3/packet-ids.json'), 'utf8').replace(/^\uFEFF/, ''));
const bots = new Map();
const gamePort=Number(process.env.UNKNOWN_VOICE_GAME_PORT||25567);
let proof;
let finished=false;
const denyMode=process.argv.includes('--expect-denied');
const singleMode=denyMode?'--deny':process.argv.includes('--tone')?'--tone':process.argv.includes('--listen')?'--listen':null;
function vi(n) { const a=[]; do { let b=n&127; n>>>=7; if(n)b|=128; a.push(b); } while(n); return Buffer.from(a); }
function str(s) { const b=Buffer.from(s); return Buffer.concat([vi(b.length), b]); }
function read(b,o=0) { let n=0,s=0,i=o; for(;i<b.length;i++){n|=(b[i]&127)<<s;if(!(b[i]&128))return[n,i+1];s+=7;if(s>35)throw Error('varint');}return null; }
function stringAt(b,o) { const h=read(b,o); return [b.subarray(h[1],h[1]+h[0]).toString(),h[1]+h[0]]; }
function finish(code) { if(finished)return;finished=true;clearTimeout(timeout); for(const b of bots.values())b.socket.destroy(); if(proof&&!proof.killed)proof.kill(); process.exitCode=code; }
const timeout=setTimeout(()=>{console.error('Voice game proof timed out');finish(1);},singleMode?90000:60000);
function secret(name,data) {
  if(denyMode){console.error('FAIL: spectator received a voice secret');finish(1);return;}
  const bot=bots.get(name); if(bot.secret)return; bot.secret=data;
  console.log(JSON.stringify({event:'voice_handshake_received',player:name}));
  if([...bots.values()].length!==(singleMode?1:2) || ![...bots.values()].every(b=>b.secret))return;
  proof=cp.spawn(path.join(__dirname,'voice-proof/target/release/unknown-voice-proof.exe'),singleMode?[singleMode]:[],{stdio:['pipe','pipe','pipe']});
  proof.on('error',e=>{console.error(e.message);finish(1);});
  proof.stdout.pipe(process.stdout); proof.stderr.pipe(process.stderr);
  proof.on('exit',code=>{proof=undefined;finish(code===0?0:1);});
  proof.stdin.end((singleMode?['VoiceA']:['VoiceA','VoiceB']).map(n=>bots.get(n).secret.toString('hex')).join('\n')+'\n');
}
function connect(name) {
  const uuid=crypto.createHash('md5').update('OfflinePlayer:'+name).digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;
  const c={socket:net.createConnection({host:'127.0.0.1',port:gamePort}),state:'login',compression:null,buffer:Buffer.alloc(0)};bots.set(name,c);
  c.send=(id,data=Buffer.alloc(0))=>{let p=Buffer.concat([vi(id),data]);if(c.compression!==null)p=p.length>=c.compression?Buffer.concat([vi(p.length),zlib.deflateSync(p)]):Buffer.concat([vi(0),p]);c.socket.write(Buffer.concat([vi(p.length),p]));};
  c.named=(type,data)=>{const id=ids[c.state].server.indexOf(type);if(id<0)throw Error(type);c.send(id,data);};
  c.socket.on('connect',()=>{const p=Buffer.alloc(2);p.writeUInt16BE(gamePort);c.send(0,Buffer.concat([vi(777),str('localhost'),p,vi(2)]));c.send(0,Buffer.concat([str(name),uuid]));});
  c.socket.on('error',e=>{console.error(name,e.message);finish(1);});
  c.socket.on('data',chunk=>{try{
    c.buffer=Buffer.concat([c.buffer,chunk]);while(c.buffer.length){const frame=read(c.buffer);if(!frame||c.buffer.length<frame[1]+frame[0])break;
      let packet=c.buffer.subarray(frame[1],frame[1]+frame[0]);c.buffer=c.buffer.subarray(frame[1]+frame[0]);
      if(c.compression!==null){const h=read(packet);packet=h[0]?zlib.inflateSync(packet.subarray(h[1])):packet.subarray(h[1]);}
      const h=read(packet),type=ids[c.state].client[h[0]],body=packet.subarray(h[1]);
      if(type?.includes('DISCONNECT')){console.error(name,'game disconnected');finish(1);return;}
      if(c.state==='login'){
        if(type==='CLIENTBOUND_LOGIN_COMPRESSION')c.compression=read(body)[0];
        if(type==='CLIENTBOUND_LOGIN_FINISHED'){c.named('SERVERBOUND_LOGIN_ACKNOWLEDGED');c.state='configuration';c.named('SERVERBOUND_CUSTOM_PAYLOAD',Buffer.concat([str('minecraft:brand'),str('horizon')]));}
      }else if(c.state==='configuration'){
        if(type==='CLIENTBOUND_PING')c.named('SERVERBOUND_PONG',body);
        if(type==='CLIENTBOUND_KEEP_ALIVE')c.named('SERVERBOUND_KEEP_ALIVE',body);
        if(type==='CLIENTBOUND_SELECT_KNOWN_PACKS')c.named('SERVERBOUND_SELECT_KNOWN_PACKS',vi(0));
        if(type==='CLIENTBOUND_FINISH_CONFIGURATION'){c.named('SERVERBOUND_FINISH_CONFIGURATION');c.state='game';}
      }else{
        if(type==='CLIENTBOUND_LOGIN'){
          console.log(JSON.stringify({event:'game_joined',player:name}));
          if(denyMode){
            const supervisor=path.join(__dirname,'../build/horizon/runtime/horizon-windows-x64/horizon-server.exe');
            const serverDir=process.env.UNKNOWN_VOICE_SERVER_DIR||path.join(__dirname,'../build/horizon/server');
            for(const command of ['unknown restore '+name,'unknown ghost '+name])cp.execFileSync(supervisor,['cmd',command,'--dir',serverDir],{windowsHide:true,stdio:'pipe'});
            setTimeout(()=>{if(c.mode!==3){console.error('FAIL: test player did not become spectator');finish(1);}else{console.log('PASS spectator excluded from native voice handshake');finish(0);}},5000);
          }
          setTimeout(()=>{const request=Buffer.from([0,0,0,20]);c.named('SERVERBOUND_CUSTOM_PAYLOAD',Buffer.concat([str('horizon:mod_net'),str('voicechat:request_secret'),vi(request.length),request]));},1000);
        }
        if(type==='CLIENTBOUND_KEEP_ALIVE')c.named('SERVERBOUND_KEEP_ALIVE',body);
        if(type==='CLIENTBOUND_GAME_EVENT'&&body[0]===3)c.mode=body.readFloatBE(1);
        if(type==='CLIENTBOUND_PLAYER_POSITION'){const t=read(body);c.named('SERVERBOUND_ACCEPT_TELEPORTATION',Buffer.concat([vi(t[0]),body.subarray(t[1],t[1]+24),body.subarray(t[1]+48,t[1]+56)]));if(ids.game.server.includes('SERVERBOUND_PLAYER_LOADED'))c.named('SERVERBOUND_PLAYER_LOADED');}
        if(type==='CLIENTBOUND_CHUNK_BATCH_FINISHED'){const rate=Buffer.alloc(4);rate.writeFloatBE(8);c.named('SERVERBOUND_CHUNK_BATCH_RECEIVED',rate);}
        if(type==='CLIENTBOUND_CUSTOM_PAYLOAD'){
          let [channel,o]=stringAt(body,0);
          if(channel==='horizon:mod_net'){
            [channel,o]=stringAt(body,o);const len=read(body,o);if(channel==='voicechat:secret')secret(name,body.subarray(len[1],len[1]+len[0]));
          }
        }
      }
    }
  }catch(e){console.error(name,e.message);finish(1);}});
}
connect('VoiceA');if(!singleMode)connect('VoiceB');
