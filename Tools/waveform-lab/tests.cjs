const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const E = require('./engine.js');
const root = path.resolve(__dirname, '../..');
function method(file, signature) {
  const text = fs.readFileSync(path.join(root, file), 'utf8');
  const start = text.indexOf(signature), brace = text.indexOf('{', start);
  assert(start >= 0 && brace >= 0);
  let depth = 1, end = brace + 1;
  for (; depth && end < text.length; end++) { if (text[end] === '{') depth++; if (text[end] === '}') depth--; }
  return text.slice(start, end);
}
// Compile the actual Java method bodies, rather than comparing two hand-written
// copies of the algorithm. This catches changes to the Android implementation.
const reader = method('TMessagesProj/src/main/java/org/telegram/ui/Components/SeekBarWaveform.java', 'private float[] calculateHeights(int count)');
const writer = method('TMessagesProj/src/main/java/org/telegram/messenger/GhostMediaTools.java', 'public static byte[] packWaveform(int[] levels)');
const cache = path.join(__dirname, 'dev', 'reference'); fs.mkdirSync(cache, {recursive:true});
fs.writeFileSync(path.join(cache, 'Reference.java'), 'import java.util.*; public class Reference { byte[] waveformBytes; ' + reader + '\n' + writer + '\n' +
 'public static void main(String[] cases) { for (String c : cases) { String[] pair=c.split(":"); int[] levels=Arrays.stream(pair[0].split(",")).mapToInt(Integer::parseInt).toArray(); byte[] packed=packWaveform(levels); Reference r=new Reference(); r.waveformBytes=packed; System.out.println(Base64.getEncoder().encodeToString(packed)+"|"+Arrays.toString(r.calculateHeights(Integer.parseInt(pair[1])))); } }}');
function run(cmd,args) { const p=spawnSync(cmd,args,{encoding:'utf8',windowsHide:true}); if(p.error)throw p.error;assert.equal(p.status,0,p.stderr);return p.stdout.trim(); }
run('javac',['-d',cache,path.join(cache,'Reference.java')]);
let state=41;
const fixtures=[Array(100).fill(0),Array(100).fill(31),Array.from({length:100},(_,i)=>i%32),Array.from({length:100},()=>{state=(Math.imul(state,1664525)+1013904223)>>>0;return state%32;})];
const cases=fixtures.flatMap(levels=>[7,40,60,99,100,120].map(count=>({levels,count})));
const lines=run('java',['-cp',cache,'Reference',...cases.map(c=>c.levels.join(',')+':'+c.count)]).split(/\r?\n/);
assert.equal(lines.length,cases.length);
cases.forEach((c,i)=>{const [packed,heights]=lines[i].split('|');const bytes=E.pack(c.levels);assert.equal(Buffer.from(bytes).toString('base64'),packed);const actual=E.heights(bytes,c.count),expected=JSON.parse(heights);assert.equal(actual.length,expected.length);actual.forEach((v,j)=>assert(Math.abs(v-expected[j])<1e-6));});
assert.throws(()=>E.pack([1,2]));assert.throws(()=>E.pack(Array(100).fill(32)));
const heart=E.heart();assert(heart.some(b=>Math.abs(b.top+b.bottom)>.1));
const bars=E.geometry(E.pack(E.project(heart)),180);assert.equal(bars.length,60);assert(bars.every(b=>b.top===-b.bottom&&b.width===2&&b.bottom<=8));
const vm=require('node:vm'),shapes=require('./approved-presets.js');
const optimized=vm.runInNewContext(fs.readFileSync(path.join(__dirname,'optimized.js'),'utf8')+';OPTIMIZED_PRESETS');
const androidFile=path.join(root,'TMessagesProj/src/main/java/org/telegram/messenger/WaveformPresets.java');
fs.writeFileSync(path.join(cache,'PresetReference.java'),'import java.util.*; import org.telegram.messenger.WaveformPresets; public class PresetReference { public static void main(String[] args) { for (WaveformPresets.Template t : WaveformPresets.Template.values()) { int[] first=WaveformPresets.create(t);System.out.println(Arrays.toString(first));first[0]=31;if(WaveformPresets.create(t)[0]==31)throw new AssertionError("Mutable preset"); } } }');
run('javac',['-d',cache,androidFile,path.join(cache,'PresetReference.java')]);
const androidPresets=run('java',['-cp',cache,'PresetReference']).split(/\r?\n/).map(s=>JSON.parse(s));
assert.equal(androidPresets.length,6);
optimized.forEach((p,i)=>{
  const generated=shapes.create(i);
  assert.equal(JSON.stringify(androidPresets[i]),JSON.stringify(p.levels));
  assert.equal(JSON.stringify(generated),JSON.stringify(p.levels));
  for(const count of [40,60,80]) {
    const read=E.heights(E.pack(p.levels),count);
    assert(read.some(v=>v>2),p.id+' loses all strokes at '+count+' bars');
  }
});
assert.equal(new Set(optimized.map(p=>JSON.stringify(p.levels))).size,6);
console.log('PASS: actual Java pack/reader comparisons; 6 compact presets match Android and lab, protected copies, visible at 3 widths and distinct.');
