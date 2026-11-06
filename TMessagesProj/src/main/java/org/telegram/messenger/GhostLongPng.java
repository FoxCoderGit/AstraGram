package org.telegram.messenger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Writes one PNG incrementally; only a scanline and a bounded IDAT buffer are retained. */
public final class GhostLongPng implements Closeable {
    private final RandomAccessFile file;
    private final int width;
    private final boolean opaque;
    private final int[] previous;
    private int height;
    private final Deflater compressor=new Deflater(Deflater.BEST_SPEED);
    private final Idat chunks=new Idat();
    private final DeflaterOutputStream pixels;
    private final byte[] scanline;
    private boolean closed;
    public GhostLongPng(File destination,int width)throws IOException{
        this(destination,width,false);
    }
    public GhostLongPng(File destination,int width,boolean opaque)throws IOException{
        if(width<=0||width>100000)throw new IOException("Invalid PNG width");
        this.width=width;this.opaque=opaque;previous=new int[width];scanline=new byte[1+width*(opaque?3:4)];
        file=new RandomAccessFile(destination,"rw");file.setLength(0);
        file.write(new byte[]{(byte)137,80,78,71,13,10,26,10});header(0);
        pixels=new DeflaterOutputStream(chunks,compressor,32768);
    }
    private void header(int height)throws IOException{
        byte[] data=ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)8).put((byte)(opaque?2:6)).put((byte)0).put((byte)0).put((byte)0).array();
        chunk("IHDR",data,data.length);
    }
    public int height(){return height;}
    public void appendRows(int[] argb,int rows)throws IOException{
        if(closed||rows<0||(long)rows*width>argb.length||(long)height+rows>Integer.MAX_VALUE)throw new IOException("Invalid PNG rows");
        for(int row=0;row<rows;row++){
            // Up filter makes repeated wallpaper/background rows cheap to compress.
            scanline[0]=2;
            for(int x=0;x<width;x++){
                int color=argb[row*width+x],old=previous[x],p=1+x*(opaque?3:4);previous[x]=color;
                scanline[p]=(byte)((color>>>16)-(old>>>16));scanline[p+1]=(byte)((color>>>8)-(old>>>8));scanline[p+2]=(byte)(color-old);
                if(!opaque)scanline[p+3]=(byte)((color>>>24)-(old>>>24));
            }
            pixels.write(scanline);height++;
        }
    }
    public void finish()throws IOException{
        if(closed||height==0)throw new IOException("PNG is empty or closed");
        try{
            pixels.finish();chunks.flushChunk();chunk("IEND",new byte[0],0);
            file.seek(8);header(height);file.getFD().sync();
        }finally{close();}
    }
    private void chunk(String name,byte[] data,int length)throws IOException{
        byte[] type=name.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(type);crc.update(data,0,length);
        file.writeInt(length);file.write(type);file.write(data,0,length);file.writeInt((int)crc.getValue());
    }
    @Override public void close()throws IOException{if(!closed){closed=true;compressor.end();file.close();}}
    private final class Idat extends OutputStream {
        final byte[] bytes=new byte[65536];int used;
        @Override public void write(int value)throws IOException{if(used==bytes.length)flushChunk();bytes[used++]=(byte)value;}
        @Override public void write(byte[] source,int start,int count)throws IOException{while(count>0){if(used==bytes.length)flushChunk();int n=Math.min(count,bytes.length-used);System.arraycopy(source,start,bytes,used,n);used+=n;start+=n;count-=n;}}
        void flushChunk()throws IOException{if(used>0){chunk("IDAT",bytes,used);used=0;}}
    }
}
