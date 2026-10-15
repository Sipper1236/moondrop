import com.formdev.flatlaf.extras.FlatSVGIcon;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Build-only renderer; run with the shaded application JAR on the source-launcher classpath. */
public class DesktopIcons {
    public static void main(String[] args) throws Exception {
        Path svg=Path.of(args.length>0?args[0]:"moondrop.svg");
        Path output=Path.of(args.length>1?args[1]:"target/native-icons");Files.createDirectories(output);
        FlatSVGIcon icon=new FlatSVGIcon(svg.toUri().toURL());
        if(!icon.hasFound())throw new IOException("Cannot load application SVG: "+svg);
        ImageIO.write(render(icon,1024),"png",output.resolve("moondrop.png").toFile());
        List<byte[]> images=new ArrayList<>();int[] sizes={16,32,48,256};
        for(int size:sizes){BufferedImage image=render(icon,size);if(size==256){var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);images.add(png.toByteArray());}else images.add(bitmap(image));}
        int length=6+16*sizes.length;for(byte[] image:images)length+=image.length;
        ByteBuffer ico=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short)0).putShort((short)1).putShort((short)sizes.length);int offset=6+16*sizes.length;
        for(int i=0;i<sizes.length;i++){int size=sizes[i];byte[] image=images.get(i);ico.put((byte)(size==256?0:size)).put((byte)(size==256?0:size)).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(image.length).putInt(offset);offset+=image.length;}
        for(byte[] image:images)ico.put(image);Files.write(output.resolve("moondrop.ico"),ico.array());
    }
    private static BufferedImage render(FlatSVGIcon icon,int size){
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=image.createGraphics();
        try{graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);graphics.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);icon.derive(size,size).paintIcon(null,graphics,0,0);}finally{graphics.dispose();}return image;
    }
    private static byte[] bitmap(BufferedImage image){
        int size=image.getWidth(),maskStride=((size+31)/32)*4;ByteBuffer bitmap=ByteBuffer.allocate(40+size*size*4+maskStride*size).order(ByteOrder.LITTLE_ENDIAN);
        bitmap.putInt(40).putInt(size).putInt(size*2).putShort((short)1).putShort((short)32).putInt(0).putInt(size*size*4+maskStride*size).putInt(0).putInt(0).putInt(0).putInt(0);
        for(int y=size-1;y>=0;y--)for(int x=0;x<size;x++){int argb=image.getRGB(x,y);bitmap.put((byte)argb).put((byte)(argb>>>8)).put((byte)(argb>>>16)).put((byte)(argb>>>24));}
        for(int y=size-1;y>=0;y--){byte[] row=new byte[maskStride];for(int x=0;x<size;x++)if((image.getRGB(x,y)>>>24)==0)row[x/8]|=(byte)(0x80>>>(x%8));bitmap.put(row);}return bitmap.array();
    }
}
