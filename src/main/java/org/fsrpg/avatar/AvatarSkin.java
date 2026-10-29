package org.fsrpg.avatar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** The vanilla 1.21.11 Java skin normalization rules, independent of the client. */
public final class AvatarSkin {
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private AvatarSkin() {}

    public static byte[] load(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 2 * 1024 * 1024)
            throw new IOException("Skin must be a PNG file of at most 2 MiB: " + path);
        try(var input=Files.newInputStream(path)){return normalize(input.readNBytes(2*1024*1024+1));}
    }

    /** Accept exactly the same 64x32 and 64x64 sizes as the official Java loader. */
    public static byte[] normalize(byte[] png) throws IOException {
        if (png.length < 33 || png.length > 2 * 1024 * 1024)
            throw new IOException("Invalid skin PNG length");
        for (int i=0;i<SIGNATURE.length;i++) if(png[i]!=SIGNATURE[i]) throw new IOException("Skin is not PNG");
        var header=ByteBuffer.wrap(png);
        if(header.getInt(8)!=13 || header.getInt(12)!=0x49484452)
            throw new IOException("Invalid PNG header");
        int width=header.getInt(16), height=header.getInt(20);
        if(width!=64 || height!=32 && height!=64)
            throw new IOException("Java skins must be 64x64 or legacy 64x32, found "+width+"x"+height);
        BufferedImage source=ImageIO.read(new ByteArrayInputStream(png));
        if(source==null || source.getWidth()!=width || source.getHeight()!=height)
            throw new IOException("Cannot decode skin PNG");
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<64;x++)image.setRGB(x,y,source.getRGB(x,y));
        boolean legacy=height==32;
        if(legacy) {
            // Exact copyRect calls used by SkinTextureDownloader.processLegacySkin.
            copy(image,4,16,16,32,4,4); copy(image,8,16,16,32,4,4);
            copy(image,0,20,24,32,4,12); copy(image,4,20,16,32,4,12);
            copy(image,8,20,8,32,4,12); copy(image,12,20,16,32,4,12);
            copy(image,44,16,-8,32,4,4); copy(image,48,16,-8,32,4,4);
            copy(image,40,20,0,32,4,12); copy(image,44,20,-8,32,4,12);
            copy(image,48,20,-16,32,4,12); copy(image,52,20,-8,32,4,12);
        }
        opaque(image,0,0,32,16);
        if(legacy) {
            boolean opaqueHat=true;
            for(int y=0;y<32;y++)for(int x=32;x<64;x++)if((image.getRGB(x,y)>>>24)<128)opaqueHat=false;
            if(opaqueHat)for(int y=0;y<32;y++)for(int x=32;x<64;x++)image.setRGB(x,y,image.getRGB(x,y)&0x00ffffff);
        }
        opaque(image,0,16,64,32); opaque(image,16,48,48,64);
        var out=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"PNG",out))throw new IOException("PNG encoder unavailable");
        return out.toByteArray();
    }
    private static void opaque(BufferedImage image,int x0,int y0,int x1,int y1) {
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)image.setRGB(x,y,image.getRGB(x,y)|0xff000000);
    }
    private static void copy(BufferedImage image,int x,int y,int dx,int dy,int width,int height) {
        for(int row=0;row<height;row++)for(int col=0;col<width;col++)
            image.setRGB(x+dx+width-1-col,y+dy+row,image.getRGB(x+col,y+row));
    }
}
