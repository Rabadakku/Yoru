package dev.yoru.ui;
import dev.yoru.game.LibretroCore;
import javax.swing.SwingUtilities;
import java.awt.event.*;
import java.util.*;
public final class GameInputTest {
 public static void main(String[] args)throws Exception {
  SwingUtilities.invokeAndWait(()-> {
   var down=new HashSet<Integer>();
   var screen=new GameScreen(null,(button,pressed)->{if(pressed)down.add(button);else down.remove(button);});
   var keys=screen.getKeyListeners()[0];
   keys.keyPressed(new KeyEvent(screen,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));
   keys.keyPressed(new KeyEvent(screen,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_X,'x'));
   if(!down.equals(Set.of(LibretroCore.RIGHT,LibretroCore.A)))throw new AssertionError("key bindings failed");
   for(var listener:screen.getFocusListeners())listener.focusLost(new FocusEvent(screen,FocusEvent.FOCUS_LOST));
   if(!down.isEmpty())throw new AssertionError("key stuck after switching windows");
   keys.keyPressed(new KeyEvent(screen,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));
   screen.stop();
   if(!down.isEmpty())throw new AssertionError("key stuck after switching tabs");
  });
  System.out.println("PASS: game inputs release on focus loss and leaving the game tab");
 }
}
