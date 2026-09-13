package dev.yoru.ui;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Pause/resume must freeze the world, while camera position is independent of travelled distance. */
public final class RouteTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static int[] render(TrainerScene scene){
        var image=new BufferedImage(scene.getWidth(),scene.getHeight(),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();scene.paint(g);g.dispose();
        return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    }
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            check(!new TrainerScene("missing-test-trainer").hasTrainerArtwork(),"Missing trainer is detected for recovery UI");
            for(var theme:dev.yoru.domain.Model.ThemeId.values()){
                Theme.apply(theme);var scene=new TrainerScene("brendan",java.time.Clock.fixed(
                    java.time.Instant.parse("2026-09-09T12:00:00Z"),java.time.ZoneOffset.UTC));scene.setSize(480,TrainerScene.HEIGHT);
                check(TrainerScene.trainerX(480)+16==240,"Trainer centered");
                for(int i=0;i<500;i++)scene.advance(true,60);
                check(scene.distance()==1500,"World travels continuously");
                scene.advance(false,60);var paused=render(scene);
                for(int i=0;i<20;i++)scene.advance(false,60);
                check(Arrays.equals(paused,render(scene)),"Pause freezes scene");
                scene.advance(true,60);check(scene.distance()==1503,"Resume does not reset route");
                check(!Arrays.equals(paused,render(scene)),"Route animates again");
                scene.setSize(720,TrainerScene.HEIGHT);check(TrainerScene.trainerX(720)+16==360,"Resizing keeps trainer centered");
                check(render(scene)[2*720+2]==Theme.ROUTE_SKY.getRGB(),"Blue sky in each theme");
                long before=scene.distance();scene.advance(true,TrainerScene.RUN_AFTER_SECONDS);
                check(scene.distance()==before+5,"Running increases speed without teleporting scenery");
            }
            check(TrainerScene.nightOpacity(java.time.LocalTime.NOON)==0,"No tint at noon");
            check(TrainerScene.nightOpacity(java.time.LocalTime.MIDNIGHT)>.5f,"Night tint at midnight");
            check(TrainerScene.nightOpacity(java.time.LocalTime.of(7,0))<TrainerScene.nightOpacity(java.time.LocalTime.of(6,0)),"Gradual dawn");
            int visible=0;
            for(int i=0;i<6000;i++)if(TrainerScene.sightingFrame(i)>=0)visible++;
            check(visible==110,"Rare bounded sighting instead of continuous visitors");
            int[] reveals={0};
            var reveal=new EncounterScene(2,false,false,()->reveals[0]++);
            reveal.advanceTo(12);check(reveals[0]==0,"Name stays hidden during reveal");
            reveal.advanceTo(24);reveal.advanceTo(30);
            check(reveals[0]==1,"Reveal completes exactly once");
        });
        System.out.println("PASS: "+checks+" route checks (camera, pause/resume, resize, four themes)");
    }
}
