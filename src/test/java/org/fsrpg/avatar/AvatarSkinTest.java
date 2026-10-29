package org.fsrpg.avatar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class AvatarSkinTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var modern=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)modern.setRGB(x,y,((x+y)*3%256)<<24|x<<16|y<<8|0x27);
        var normalized=read(AvatarSkin.normalize(png(modern)));
        for(int y=0;y<64;y++)for(int x=0;x<64;x++) {
            boolean base=x<32&&y<16 || y>=16&&y<32 || x>=16&&x<48&&y>=48;
            int expected=base?modern.getRGB(x,y)|0xff000000:modern.getRGB(x,y);
            check(normalized.getRGB(x,y)==expected,"modern alpha and UV pixels "+x+","+y);
        }
        var old=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<64;x++)old.setRGB(x,y,0xff000000|x<<16|y<<8|0x31);
        var converted=read(AvatarSkin.normalize(png(old)));
        check(converted.getWidth()==64&&converted.getHeight()==64,"legacy becomes modern skin size");
        int[][] copyRects={{4,16,16,32,4,4},{8,16,16,32,4,4},{0,20,24,32,4,12},{4,20,16,32,4,12},{8,20,8,32,4,12},{12,20,16,32,4,12},{44,16,-8,32,4,4},{48,16,-8,32,4,4},{40,20,0,32,4,12},{44,20,-8,32,4,12},{48,20,-16,32,4,12},{52,20,-8,32,4,12}};
        for(int[] r:copyRects)for(int y=0;y<r[5];y++)for(int x=0;x<r[4];x++)
            check(converted.getRGB(r[0]+r[2]+r[4]-1-x,r[1]+r[3]+y)==old.getRGB(r[0]+x,r[1]+y),"legacy mirrored limb face matches vanilla");
        check(converted.getRGB(42,7)>>>24==0,"old fully opaque hat area made transparent");
        check(converted.getRGB(10,40)==0,"old skin has no invented jacket layer");
        old.setRGB(42,7,0x7fabcdef);
        var translucent=read(AvatarSkin.normalize(png(old)));
        check(translucent.getRGB(42,7)==0x7fabcdef,"intentional old hat alpha retained");
        check(translucent.getRGB(45,7)>>>24==255,"intentional old hat prevents global transparency hack");
        rejects(png(new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB)),"HD Java skin has explicit size error");
        rejects(png(new BufferedImage(64,48,BufferedImage.TYPE_INT_ARGB)),"unsupported aspect ratio rejected");
        rejects(new byte[64],"not PNG rejected");
        System.out.println("AvatarSkinTest passed: "+checks+" checks");
    }
    static byte[] png(BufferedImage image) throws IOException { var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray(); }
    private static BufferedImage read(byte[] bytes)throws IOException{return ImageIO.read(new ByteArrayInputStream(bytes));}
    private static void rejects(byte[] bytes,String message)throws IOException {
        try {AvatarSkin.normalize(bytes);throw new AssertionError(message);}catch(IOException expected){check(true,message);}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
