const {chromium}=require('playwright'),path=require('node:path'),{pathToFileURL}=require('node:url'),assert=require('node:assert/strict');
(async()=>{
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const page=await browser.newPage({viewport:{width:1280,height:1100}}),errors=[];
page.on('pageerror',e=>errors.push(e.message));await page.goto(pathToFileURL(path.resolve(__dirname,'../index.html')).href);
assert.equal(await page.locator('.preset-card').count(),6);
assert.equal(await page.locator('#preset option').count(),6);
await page.locator('.preset-gallery').screenshot({path:path.join(__dirname,'../gallery.png')});
for(let i=0;i<6;i++){
await page.locator('#preset').selectOption('fit-'+i);
const values=JSON.parse(await page.locator('#data').inputValue());
const expected=await page.evaluate(i=>OPTIMIZED_PRESETS[i].levels,i);assert.deepEqual(values,expected);
}
await page.setViewportSize({width:390,height:844});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);
await browser.close();console.log('PASS: exactly six compact presets, matching Android; gallery, mobile layout and no page errors.');
})().catch(e=>{console.error(e);process.exit(1)});
