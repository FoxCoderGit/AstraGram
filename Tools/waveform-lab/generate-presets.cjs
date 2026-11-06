// Reproducible bridge: the site and the Android app share exactly these samples.
const fs=require('node:fs'),path=require('node:path');
const shapes=require('./approved-presets.js');
const presets=shapes.specs.map((s,i)=>({levels:shapes.create(i),id:s.id,name:s.name,span:s.span/100,note:s.note}));
fs.writeFileSync(path.join(__dirname,'optimized.js'),'const OPTIMIZED_PRESETS = '+JSON.stringify(presets,null,2)+';\n');
const java=`package org.telegram.messenger;

/** Generated from the six compact profiles selected in Tools/waveform-lab.
 * Ordinary symmetric Telegram bars; asymmetric details and holes are approximate.
 * Keep the lab and this file in sync by rerunning the generator. */
public final class WaveformPresets {
    public enum Template {
        ${presets.map(p=>p.id).join(', ')}
    }
    private WaveformPresets() { }
    private static final int[][] LEVELS = {
${presets.map(p=>'        // '+p.id+'; occupied width '+Math.round(p.span*100)+'%\n        {'+p.levels.join(',')+'}').join(',\n')}
    };
    public static int[] create(Template template) {
        return LEVELS[template.ordinal()].clone();
    }
}
`;
fs.writeFileSync(path.resolve(__dirname,'../../TMessagesProj/src/main/java/org/telegram/messenger/WaveformPresets.java'),java);
console.log('Generated '+presets.length+' approved compact presets for the lab and Android.');
