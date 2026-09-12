package dev.yoru.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.swing.*;

/** Local-only visual artifacts, including real-time frames for checking the gait. */
public final class RoutePreview {
    private static BufferedImage render(JPanel panel,int width){
        panel.setSize(width,200);
        var image=new BufferedImage(width,200,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();panel.paint(g);g.dispose();return image;
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of("build/route-preview");Files.createDirectories(out);
        SwingUtilities.invokeAndWait(()->{
            try{
                Theme.install();
                var noon=Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"),ZoneOffset.UTC);
                var day=new TrainerScene("brendan",noon);
                var night=new TrainerScene("brendan",Clock.fixed(Instant.parse("2026-09-09T23:00:00Z"),ZoneOffset.UTC));
                var contact=new BufferedImage(840,800,BufferedImage.TYPE_INT_RGB);
                var g=contact.createGraphics();
                g.drawImage(render(day,840),0,0,null);
                for(int i=0;i<40;i++)day.advance(true,60);
                g.drawImage(render(day,840),0,200,null);
                g.drawImage(render(night,840),0,400,null);
                while(TrainerScene.sightingFrame(day.distance()/3)!=50)day.advance(true,60);
                g.drawImage(render(day,840),0,600,null);g.dispose();
                ImageIO.write(contact,"png",out.resolve("route-states.png").toFile());
                var walking=new TrainerScene("brendan",noon);
                var gait=new BufferedImage(4*120,200,BufferedImage.TYPE_INT_RGB);
                var gaitGraphics=gait.createGraphics();
                for(int i=0;i<4;i++){
                    walking.advance(true,60);walking.advance(true,60);
                    gaitGraphics.drawImage(render(walking,120),i*120,0,null);
                }
                gaitGraphics.dispose();ImageIO.write(gait,"png",out.resolve("gait.png").toFile());
                var writer=ImageIO.getImageWritersByFormatName("gif").next();
                try(var stream=ImageIO.createImageOutputStream(out.resolve("walking.gif").toFile())){
                    writer.setOutput(stream);writer.prepareWriteSequence(null);
                    for(int i=0;i<96;i++){
                        walking.advance(true,60);var frame=render(walking,840);
                        var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame),null);
                        String format=metadata.getNativeMetadataFormatName();
                        var root=(IIOMetadataNode)metadata.getAsTree(format);
                        var control=(IIOMetadataNode)root.getElementsByTagName("GraphicControlExtension").item(0);
                        control.setAttribute("delayTime","7");control.setAttribute("disposalMethod","none");
                        if(i==0){
                            var extensions=new IIOMetadataNode("ApplicationExtensions");
                            var loop=new IIOMetadataNode("ApplicationExtension");
                            loop.setAttribute("applicationID","NETSCAPE");loop.setAttribute("authenticationCode","2.0");
                            loop.setUserObject(new byte[]{1,0,0});extensions.appendChild(loop);root.appendChild(extensions);
                        }
                        metadata.setFromTree(format,root);writer.writeToSequence(new IIOImage(frame,null,metadata),null);
                    }
                    writer.endWriteSequence();
                }finally{writer.dispose();}
                var reveal=new EncounterScene(2,true,false,()->{});
                var stages=new BufferedImage(1080,200,BufferedImage.TYPE_INT_RGB);g=stages.createGraphics();
                for(int i=0;i<3;i++){reveal.advanceTo(i*12);g.drawImage(render(reveal,360),i*360,0,null);}
                g.dispose();ImageIO.write(stages,"png",out.resolve("encounter.png").toFile());
                System.out.println("Rendered route states, walking.gif and encounter stages");
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
}
