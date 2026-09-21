package dev.yoru.ui;
import dev.yoru.application.Analytics;
import dev.yoru.domain.Model.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A week laid out against the clock: hour rules down the side, a column per
 * day, and every recorded session drawn where it actually happened.
 *
 * Recorded time is the point. A schedule that only shows what you planned tells
 * you nothing about the week you had, so sessions render as solid blocks and
 * planned blocks render as outlines behind them — the gap between the two is
 * the information worth seeing.
 */
final class ScheduleGrid extends JPanel {
    /** The designed measurements: floors that the text in them may raise. */
    private static final int GUTTER=46, HEADER=30, MIN_ROW=34, MIN_ROW_TIGHT=24, FLOOR_ROW=18;
    /** Grab band at a block's edges, and the quarter-hour the grid snaps to. */
    private static final int EDGE=7, SNAP=15;
    /**
     * The shortest a live session is drawn.
     *
     * A running session ends at "now", so one that has just started collapses to
     * a sliver the now-line cuts through and it reads as a rendering fault. It
     * is grown up from the now line instead, to a height that clears the line
     * and leaves room to say it is still running.
     */
    private static final int RUNNING_MIN=Theme.SPACE_XXL;

    /** The shortest any block is drawn, so a span of a few minutes is still a mark on the grid. */
    private static final int MIN_BLOCK=14;

    /** How far the ink of a label reaches below its baseline: the air the growth must leave. */
    private static final int LABEL_DESCENT=Theme.SPACE_XS;

    // ---- measurements ------------------------------------------------------
    //
    // The grid is drawn rather than laid out, so each number above is a floor
    // rather than the answer: the text size the reader chose (#31) grows the
    // lettering and would otherwise leave the room it is drawn in exactly where
    // it was. At 200% the day names were printed over the lane labels beneath
    // them, the hours ran into the grid, and a block's own title sat outside
    // the block. The faces are measured once per face, not once per JVM: the
    // reader can change the size while the app is open.

    private static Font measuredFace;
    private static FontMetrics captionHeld, bodyHeld;

    private static void measure() {
        var face=Theme.captionFont();
        if(face.equals(measuredFace)) return;
        var scratch=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
        captionHeld=scratch.getFontMetrics(face);
        bodyHeld=scratch.getFontMetrics(Theme.bodyFont());
        scratch.dispose();
        measuredFace=face;
    }
    private static FontMetrics caption() { measure(); return captionHeld; }
    private static FontMetrics body() { measure(); return bodyHeld; }

    /** The column the hours are written in, left of the grid. */
    private static int gutter() { return Math.max(Theme.grow(GUTTER),caption().stringWidth("00")+Theme.SPACE_XL); }

    /** The band over the grid: the day's name, and the two lane labels under it. */
    private static int header() { return Math.max(Theme.grow(HEADER),2*caption().getHeight()+Theme.SPACE_XS); }

    private static int minRow() { return Math.max(Theme.grow(MIN_ROW),caption().getHeight()+Theme.SPACE_MD); }
    private static int tightRow() { return Math.max(Theme.grow(MIN_ROW_TIGHT),caption().getHeight()+Theme.SPACE_XS); }
    private static int floorRow() { return Math.max(Theme.grow(FLOOR_ROW),caption().getHeight()); }

    /**
     * A block's own two lines: where each sits, and the height each one needs.
     *
     * A block writes its own name, and then its length or the word "running",
     * in the first lines under its top edge, and for a block too short to hold
     * a chart those lines are all it has to say. Two parts of the grid read
     * them: the painter below, and the room a live block has to leave the block
     * it lands on (see {@link #liveCeiling}), so nothing may be drawn over a
     * short block's only words.
     */
    private static int titleBaseline() { return body().getAscent()+Theme.SPACE_XS; }
    private static int lengthBaseline() { return titleBaseline()+caption().getHeight(); }
    private static int titleRoom() { return titleBaseline()+Theme.grow(LABEL_DESCENT); }
    private static int lengthRoom() { return lengthBaseline()+Theme.grow(LABEL_DESCENT); }

    private static int runningMin() { return Math.max(Theme.grow(RUNNING_MIN),titleRoom()); }
    private static int minBlock() { return Theme.grow(MIN_BLOCK); }

    /**
     * The mark a shortened title ends in: one glyph, U+2026.
     *
     * Not three ASCII periods — they are three separate glyphs, so they sit at
     * whatever spacing the font gives them and a screen reader may read them
     * out one by one.
     */
    private static final String ELLIPSIS="…";

    /** How the grid asks for changes; the tracker stays the only mutation boundary. */
    interface Edits {
        void create(Instant start,Instant end);
        void update(UUID blockId,Instant start,Instant end);
        /** A recorded session was dragged. Separate call: correcting history is not moving a plan. */
        default void updateSession(UUID sessionId,Instant start,Instant end) { }
        /** Double-click: open the full editor, where it can also be deleted. */
        default void open(UUID id,boolean recorded) { }
    }

    private enum Mode { NONE, CREATE, MOVE, RESIZE_TOP, RESIZE_BOTTOM }
    private Mode mode=Mode.NONE;
    private UUID activeId;
    private int activeDay, anchorMinute, dragMinute, grabOffset, heldLength;
    private boolean activeRecorded;

    /** One drawable span, already clipped to a single day. */
    private record Segment(int day,int fromMinute,int toMinute,String label,Color colour,
                           boolean planned,boolean running,boolean repeating,UUID id) { }

    private final ZoneId zone;
    private final LocalDate weekStart;
    private final LocalDate today;
    private final List<Segment> segments=new ArrayList<>();
    private final int startHour,endHour;
    private final Instant now;

    ScheduleGrid(State state,LocalDate weekStart,ZoneId zone,Instant now) { this(state,weekStart,zone,now,null); }

    ScheduleGrid(State state,LocalDate weekStart,ZoneId zone,Instant now,Edits edits) {
        this.zone=zone;
        this.weekStart=weekStart;
        this.now=now;
        this.today=LocalDate.now(zone);
        setOpaque(false);
        setToolTipText("");

        var activities=state.activities();
        for(var session:state.sessions()) {
            // Sessions below the minimum are excluded from every total and from
            // rewards, so drawing them here said one thing while the numbers
            // beside them said another (#34). A running session always counts —
            // it has not finished being short yet.
            if(!Analytics.counts(session,state,now)) continue;
            int index=indexOf(activities,session.activityId());
            add(session.start(),session.end()==null?now:session.end(),
                name(activities,session.activityId()),colour(index),false,session.end()==null,session.id());
        }
        for(var block:state.blocks())
            add(block.start(),block.end(),name(activities,block.activityId()),
                colour(indexOf(activities,block.activityId())),true,false,block.id());
        // The weekly template, expanded onto this week's dates. Derived, so these
        // are drawn but never dragged: moving one would have to edit the rule for
        // every week, which is a different action from nudging one Thursday.
        for(var occurrence:Analytics.occurrences(state,weekStart,zone))
            add(occurrence.start(),occurrence.end(),name(activities,occurrence.activityId()),
                colour(indexOf(activities,occurrence.activityId())),true,false,true,occurrence.recurringId());

        // Frame the day around what is actually there, with a sane default window.
        int earliest=8, latest=18;
        for(var s:segments) {
            earliest=Math.min(earliest,s.fromMinute()/60);
            latest=Math.max(latest,(s.toMinute()+59)/60);
        }
        startHour=Math.max(0,earliest-1);
        endHour=Math.min(24,Math.max(startHour+6,latest+1));

        int rows=endHour-startHour;
        setPreferredSize(new Dimension(880,header()+rows*minRow()+Theme.SPACE_SM));
        setMinimumSize(new Dimension(420,header()+rows*tightRow()+Theme.SPACE_SM));
        getAccessibleContext().setAccessibleName("Week of "+weekStart+", recorded sessions, planned blocks and the weekly template");
        if(edits!=null) install(edits);
    }

    private static int snap(int minute) { return Math.max(0,Math.min(24*60,Math.round(minute/(float)SNAP)*SNAP)); }
    private int minuteAt(int y) { return startHour*60+(y-header())*60/Math.max(1,rowHeight()); }
    private int columnAt(int x) { return Math.max(0,Math.min(6,(x-gutter())/Math.max(1,columnWidth()))); }
    private boolean inGrid(Point at) { return at.x>=gutter() && at.y>=header() && at.y<=yFor(endHour*60); }
    private Instant instantAt(int day,int minute) {
        return weekStart.plusDays(day).atStartOfDay(zone).plusMinutes(minute).toInstant();
    }
    /**
     * What the pointer can act on: a planned block, or a recorded session.
     *
     * Recorded time used to be inert here, on the reasoning that history is not a
     * plan. In use that was wrong — you notice a session is wrong while looking
     * at the week, and then had to go somewhere else to fix it (#33). Repeating
     * occurrences stay inert, because dragging one would edit every week, and a
     * running session stays inert because its end has not happened yet.
     */
    private Segment editableAt(Point at) {
        for(var s:segments)
            if(!s.repeating()&&!s.running()&&boundsOf(s).contains(at)) return s;
        return null;
    }

    /** Only for tests: how many spans this grid actually drew. */
    int drawn() { return segments.size(); }

    /** Only for tests: the id the pointer could act on at this point. */
    UUID actionableAt(Point at) { var hit=editableAt(at); return hit==null?null:hit.id(); }

    /** Only for tests: the middle of a drawn span, so a drag can be aimed at it. */
    Point pointOn(UUID id) {
        for(var s:segments) if(s.id().equals(id)) {
            var box=boundsOf(s);
            return new Point(box.x+box.width/2,box.y+box.height/2);
        }
        return null;
    }

    private void install(Edits edits) {
        var handler=new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                var hit=editableAt(e.getPoint());
                if(hit!=null&&e.getClickCount()>=2) { edits.open(hit.id(),!hit.planned()); return; }
                if(hit!=null) {
                    var box=boundsOf(hit);
                    activeId=hit.id();
                    activeRecorded=!hit.planned();
                    activeDay=hit.day();
                    heldLength=hit.toMinute()-hit.fromMinute();
                    if(e.getY()-box.y<=EDGE) { mode=Mode.RESIZE_TOP; anchorMinute=hit.toMinute(); }
                    else if(box.y+box.height-e.getY()<=EDGE) { mode=Mode.RESIZE_BOTTOM; anchorMinute=hit.fromMinute(); }
                    else { mode=Mode.MOVE; grabOffset=minuteAt(e.getY())-hit.fromMinute(); }
                    dragMinute=minuteAt(e.getY());
                } else if(inGrid(e.getPoint())) {
                    mode=Mode.CREATE;
                    activeDay=columnAt(e.getX());
                    anchorMinute=snap(minuteAt(e.getY()));
                    dragMinute=anchorMinute+SNAP;
                }
                repaint();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if(mode==Mode.NONE) return;
                dragMinute=minuteAt(e.getY());
                if(mode==Mode.MOVE) activeDay=columnAt(e.getX());
                repaint();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if(mode==Mode.NONE) return;
                var span=provisional();
                var finished=mode;
                var id=activeId;
                boolean recorded=activeRecorded;
                int day=activeDay;
                mode=Mode.NONE;
                activeId=null;
                activeRecorded=false;
                if(span!=null&&span[1]>span[0]) {
                    if(finished==Mode.CREATE) edits.create(instantAt(day,span[0]),instantAt(day,span[1]));
                    else if(id!=null&&recorded) edits.updateSession(id,instantAt(day,span[0]),instantAt(day,span[1]));
                    else if(id!=null) edits.update(id,instantAt(day,span[0]),instantAt(day,span[1]));
                } else repaint();
            }
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                var hit=editableAt(e.getPoint());
                if(hit==null) { setCursor(Cursor.getDefaultCursor()); return; }
                var box=boundsOf(hit);
                boolean edge=e.getY()-box.y<=EDGE||box.y+box.height-e.getY()<=EDGE;
                setCursor(Cursor.getPredefinedCursor(edge?Cursor.N_RESIZE_CURSOR:Cursor.MOVE_CURSOR));
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    private static int indexOf(List<Activity> activities,UUID id) {
        for(int i=0;i<activities.size();i++) if(activities.get(i).id().equals(id)) return i;
        return 0;
    }
    private static String name(List<Activity> activities,UUID id) {
        return activities.stream().filter(a->a.id().equals(id)).map(Activity::name).findFirst().orElse("Activity");
    }

    /** Stable per-activity colour drawn from the active palette. */
    static Color colour(int index) {
        Color[] wheel={Theme.CYAN,Theme.GOLD,Theme.PURPLE,Theme.HEAT[4],Theme.HEAT[2],Theme.HEAT[3]};
        return wheel[Math.floorMod(index,wheel.length)];
    }

    /** Splits a span at local midnight so each piece belongs to exactly one column. */
    private void add(Instant from,Instant to,String label,Color colour,boolean planned,boolean running,UUID id) {
        add(from,to,label,colour,planned,running,false,id);
    }

    private void add(Instant from,Instant to,String label,Color colour,boolean planned,boolean running,boolean repeating,UUID id) {
        var cursor=from;
        while(cursor.isBefore(to)) {
            var day=cursor.atZone(zone).toLocalDate();
            var midnight=day.plusDays(1).atStartOfDay(zone).toInstant();
            var slice=midnight.isBefore(to)?midnight:to;
            int index=(int)java.time.temporal.ChronoUnit.DAYS.between(weekStart,day);
            if(index>=0&&index<7) {
                var localFrom=cursor.atZone(zone).toLocalTime();
                int fromMinute=localFrom.getHour()*60+localFrom.getMinute();
                int toMinute=fromMinute+(int)Duration.between(cursor,slice).toMinutes();
                segments.add(new Segment(index,fromMinute,Math.min(24*60,Math.max(toMinute,fromMinute+1)),
                    label,colour,planned,running,repeating,id));
            }
            cursor=slice;
        }
    }

    private int columnWidth() { return Math.max(40,(getWidth()-gutter())/7); }
    private int rowHeight() { return Math.max(floorRow(),(getHeight()-header()-Theme.SPACE_SM)/Math.max(1,endHour-startHour)); }
    private int yFor(int minute) { return header()+(minute-startHour*60)*rowHeight()/60; }

    /** Where the two lanes of a day begin. Plan on the left, actual on the right. */
    private int laneX(int day,boolean planned) {
        int colW=columnWidth();
        return gutter()+day*colW+(planned?0:colW/2);
    }
    private int laneWidth() { return Math.max(12,columnWidth()/2); }

    private Rectangle boundsOf(Segment s) {
        int y=yFor(s.fromMinute());
        int bottom=yFor(s.toMinute());
        int height=Math.max(minBlock(),bottom-y);
        // A live session grows up from the now-line, never down past it: the
        // block may not claim minutes that have not happened yet, and anchoring
        // it keeps it joined to the recorded block above rather than floating
        // under it. The growth gives way to that block's own words: a session
        // that had just started was drawn straight over the session that had
        // just ended and cut its "01:30" in half, so the live block reaches only
        // as far up as the text above it leaves blank — and never starts below
        // the line itself. Where the two leave it less than a block's floor, the label
        // wins and the live block is drawn short: a pill on the now-line still
        // says a session is running, a covered label says nothing at all.
        if(s.running()) {
            // The two bounds are the text above and the line below; where they
            // meet, the live block is as tall as the gap between them allows.
            int ceiling=Math.min(liveCeiling(s,bottom),bottom-Theme.HAIRLINE);
            y=Math.max(bottom-Math.max(runningMin(),height),ceiling);
            height=bottom-y;
        }
        // Two lanes per day (#35): the plan and what actually happened, side by
        // side. They used to share a column with recorded time drawn on top,
        // which hid the plan in exactly the case worth looking at.
        return new Rectangle(laneX(s.day(),s.planned())+3,y,laneWidth()-6,height);
    }

    /**
     * The lowest a live block may reach up its lane, from the text it would land on.
     *
     * Every block writes its name, and then its length or the word "running", in
     * the first few pixels under its own top edge, and for a block too short to
     * hold a chart those lines are all it has to say. The ceiling is the foot of
     * that text on every block the live one could reach, so the growth still
     * makes a session that has just started visible without hiding a label: a
     * block with room to spare sets nothing, a short one that is all label sets
     * the whole of it.
     */
    private int liveCeiling(Segment running,int bottom) {
        int ceiling=bottom-runningMin();
        for(var other:segments) {
            // The plan lane is never reached, and a block starting below the
            // now-line is never grown into: only what this block lands on counts.
            if(other.running()||other.planned()||other.day()!=running.day()) continue;
            var box=boundsOf(other);
            if(box.y>=bottom) continue;
            ceiling=Math.max(ceiling,box.y+labelFoot(box.height));
        }
        return ceiling;
    }

    /** How far under its own top edge a block's text reaches, at the height it is drawn. */
    private static int labelFoot(int height) {
        if(height>=lengthRoom()) return lengthRoom();
        if(height>=titleRoom()) return titleRoom();
        return 0;
    }

    @Override public String getToolTipText(MouseEvent event) {
        for(var s:segments) {
            if(!boundsOf(s).contains(event.getPoint())) continue;
            String from=String.format("%02d:%02d",s.fromMinute()/60,s.fromMinute()%60);
            String to=String.format("%02d:%02d",s.toMinute()/60,s.toMinute()%60);
            long minutes=s.toMinute()-s.fromMinute();
            return (s.repeating()?"Every "+weekStart.plusDays(s.day()).getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL,java.util.Locale.ENGLISH)+" · "
                    :s.planned()?"Planned · ":s.running()?"Running · ":"Recorded · ")
                +s.label()+"  "+from+"–"+to+"  ("+minutes+"m)"
                +(s.repeating()||s.running()?"":"  ·  drag to move, double-click to edit or delete");
        }
        return null;
    }

    /** The span the current drag would commit, as {fromMinute, toMinute}. */
    private int[] provisional() {
        if(mode==Mode.NONE) return null;
        int pointer=snap(dragMinute);
        return switch(mode) {
            case CREATE -> new int[]{Math.min(anchorMinute,pointer),Math.max(anchorMinute,pointer)};
            case RESIZE_TOP -> new int[]{Math.min(pointer,anchorMinute-SNAP),anchorMinute};
            case RESIZE_BOTTOM -> new int[]{anchorMinute,Math.max(pointer,anchorMinute+SNAP)};
            case MOVE -> {
                int from=Math.max(0,Math.min(24*60-heldLength,snap(pointer-grabOffset)));
                yield new int[]{from,from+heldLength};
            }
            default -> null;
        };
    }

    protected void paintComponent(Graphics graphics) {
        var g=(Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        int w=getWidth(),colW=columnWidth(),rowH=rowHeight();

        // Day headers.
        g.setFont(Theme.captionFont());
        for(int d=0;d<7;d++) {
            var day=weekStart.plusDays(d);
            boolean isToday=day.equals(today);
            int x=gutter()+d*colW;
            if(isToday) {
                g.setColor(Theme.shade(Theme.PANEL,Theme.DARK?12:-10));
                g.fillRect(x,0,colW,getHeight());
            }
            g.setColor(isToday?Theme.CYAN:Theme.MUTED);
            String text=day.getDayOfWeek().toString().substring(0,3)+" "+day.getDayOfMonth();
            g.drawString(text,x+(colW-g.getFontMetrics().stringWidth(text))/2,caption().getAscent()+Theme.SPACE_XS);
            // Lane labels, only where there is room for them to be read.
            if(colW>=2*(caption().stringWidth("ACTUAL")+Theme.SPACE_SM)) {
                g.setFont(Theme.captionFont());
                // Muted as it stands on a light ground, a shade back on a dark
                // one. Lightening muted on Linen and Sakura measured 3.3:1 —
                // the faintest text in the app, on the two themes that need
                // darkening rather than more light. Midnight and Ember keep
                // exactly the shade they had.
                g.setColor(Theme.DARK?Theme.shade(Theme.MUTED,-25):Theme.MUTED);
                var metrics=g.getFontMetrics();
                int lane=caption().getHeight()+caption().getAscent()+Theme.SPACE_XS;
                g.drawString("PLAN",x+(colW/2-metrics.stringWidth("PLAN"))/2,lane);
                g.drawString("ACTUAL",x+colW/2+(colW/2-metrics.stringWidth("ACTUAL"))/2,lane);
                g.setFont(Theme.captionFont());
            }
        }

        // Hour rules, with a lighter half-hour line between them.
        g.setFont(Theme.captionFont());
        for(int hour=startHour;hour<=endHour;hour++) {
            int y=yFor(hour*60);
            g.setColor(Theme.LINE);
            g.drawLine(gutter(),y,w,y);
            g.setColor(Theme.MUTED);
            g.drawString(String.format("%02d",hour),(gutter()-caption().stringWidth("00"))/2,y+caption().getAscent()/2);
            if(hour<endHour) {
                g.setColor(Theme.shade(Theme.LINE,Theme.DARK?-8:10));
                int half=y+rowH/2;
                for(int x=gutter();x<w;x+=6) g.drawLine(x,half,x+2,half);
            }
        }
        g.setColor(Theme.LINE);
        for(int d=0;d<=7;d++) g.drawLine(gutter()+d*colW,header(),gutter()+d*colW,yFor(endHour*60));
        // The lane divider, lighter than the day rule so a day still reads as one.
        g.setColor(Theme.shade(Theme.LINE,Theme.DARK?-10:12));
        for(int d=0;d<7;d++) {
            int x=gutter()+d*colW+colW/2;
            for(int y=header();y<yFor(endHour*60);y+=5) g.drawLine(x,y,x,y+2);
        }

        // Planned first, so recorded time reads on top of the intention.
        for(var s:segments) if(s.planned()) paintSegment(g,s);
        for(var s:segments) if(!s.planned()) paintSegment(g,s);

        // Now line, only when the visible week contains today.
        int todayIndex=(int)java.time.temporal.ChronoUnit.DAYS.between(weekStart,today);
        if(todayIndex>=0&&todayIndex<7) {
            var localNow=now.atZone(zone).toLocalTime();
            int minute=localNow.getHour()*60+localNow.getMinute();
            if(minute>=startHour*60&&minute<=endHour*60) {
                int y=yFor(minute);
                g.setColor(Theme.DANGER);
                int chip=caption().getHeight()+Theme.SPACE_XS;
                g.fillRoundRect(2,y-chip/2,caption().stringWidth("00:00")+Theme.SPACE_SM,chip,
                    Theme.RADIUS,Theme.RADIUS);
                g.setColor(Theme.DARK?Theme.BG:Theme.PANEL);
                g.setFont(Theme.captionFont());
                g.drawString(String.format("%02d:%02d",minute/60,minute%60),2+Theme.SPACE_XS,y+caption().getAscent()/2);
                g.setColor(Theme.DANGER);
                g.drawLine(gutter(),y,w,y);
            }
        }
        // The block being dragged, drawn over everything so it is never hidden.
        var span=provisional();
        if(span!=null) {
            // Drawn in the lane the drag belongs to: a new plan on the left, a
            // session being corrected on the right, so the preview lands where
            // the result will.
            boolean plannedLane=!activeRecorded;
            int x=laneX(activeDay,plannedLane)+3;
            int y=yFor(span[0]);
            int height=Math.max(10,yFor(span[1])-y);
            g.setColor(Theme.CYAN);
            g.drawRoundRect(x,y,laneWidth()-6,height,8,8);
            g.setFont(Theme.captionFont());
            g.drawString(String.format("%02d:%02d – %02d:%02d",span[0]/60,span[0]%60,span[1]/60,span[1]%60),
                x+4,y+caption().getAscent()+Theme.SPACE_XS);
        }
        g.dispose();
    }

    private void paintSegment(Graphics2D g,Segment s) {
        var box=boundsOf(s);
        if(s.planned()) {
            g.setColor(Theme.shade(s.colour(),Theme.DARK?-70:60));
            var plain=g.getStroke();
            // Dashed: a repeating block is a rule showing through, not a thing
            // sitting on this particular Thursday that you can pick up.
            if(s.repeating()) g.setStroke(new java.awt.BasicStroke(1,java.awt.BasicStroke.CAP_BUTT,
                java.awt.BasicStroke.JOIN_MITER,10,new float[]{4,4},0));
            g.drawRoundRect(box.x,box.y,box.width,box.height,8,8);
            g.setStroke(plain);
            g.setFont(Theme.captionFont());
            g.setColor(Theme.MUTED);
            if(box.height>=titleRoom())
                g.drawString(s.repeating()?"weekly":"planned",box.x+6,box.y+caption().getAscent()+Theme.SPACE_XS);
            return;
        }
        g.setColor(s.colour());
        g.fillRoundRect(box.x,box.y,box.width,box.height,8,8);
        if(s.running()) {
            // A live session is the one block still being written. Its edge used
            // to be the ground colour, which punched a hole through the grid and
            // left a stray hollow pill under the block above it; the edge is now
            // a shade of the block's own colour, thick enough to read on every
            // palette, and the block says what it is.
            g.setColor(Theme.shade(s.colour(),Theme.DARK?60:-60));
            g.setStroke(new java.awt.BasicStroke(Theme.RING));
            g.drawRoundRect(box.x+Theme.RING/2,box.y+Theme.RING/2,
                box.width-Theme.RING,box.height-Theme.RING,Theme.RADIUS,Theme.RADIUS);
            g.setStroke(new java.awt.BasicStroke(Theme.HAIRLINE));
        }
        // Labels only where they will not be clipped mid-glyph, and shortened on
        // grapheme boundaries when they are. The heights are the ones liveCeiling
        // reads to know how much room a live block has to leave.
        if(box.height<titleRoom()) return;
        g.setColor(Theme.DARK?Theme.BG:Theme.PANEL);
        g.setFont(Theme.bodyFont());
        g.drawString(clip(g,s.label(),box.width-12),box.x+6,box.y+titleBaseline());
        if(box.height>=lengthRoom()) {
            g.setFont(Theme.captionFont());
            g.drawString(s.running()?"running"
                :Analytics.duration((s.toMinute()-s.fromMinute())*60L).substring(0,5),box.x+6,box.y+lengthBaseline());
        }
    }

    /**
     * Shortens a title until it fits, stepping back a whole grapheme at a time.
     *
     * Dropping one char at a time cuts a surrogate pair in half, or separates a
     * base letter from the combining mark that gives it its accent, and the
     * orphaned half is drawn as the replacement glyph — an emoji or accented
     * title ended in "�" in the grid. BreakIterator walks the string in the units
     * a reader sees, so a cluster is kept or dropped whole.
     *
     * The candidate is measured with the ellipsis already attached, the same way
     * FocusBars.clip does it: a title that fits but leaves no room for the mark
     * gives up one more grapheme rather than running under the next column.
     */
    static String clip(Graphics2D g,String text,int width) {
        var metrics=g.getFontMetrics();
        if(text.isEmpty()||metrics.stringWidth(text)<=width) return text;
        var characters=java.text.BreakIterator.getCharacterInstance();
        characters.setText(text);
        int end=characters.last();
        while(end>0&&metrics.stringWidth(text.substring(0,end)+ELLIPSIS)>width) end=characters.previous();
        return text.substring(0,end)+ELLIPSIS;
    }

    /**
     * The grid's accessible surface.
     *
     * The blocks are painted, not components, so a bare panel tells a screen
     * reader the week and none of the titles in it. Each drawn span is exposed
     * as a child named with its whole label — the string the brush above may
     * have shortened — so a title clipped to fit the drawing is still heard in
     * full, along with when it happened.
     */
    private final class GridAccessible extends AccessibleJPanel {
        @Override public int getAccessibleChildrenCount() { return segments.size(); }

        @Override public Accessible getAccessibleChild(int index) {
            if(index<0||index>=segments.size()) return null;
            var s=segments.get(index);
            var label=new JLabel();
            label.getAccessibleContext().setAccessibleName(s.label());
            label.getAccessibleContext().setAccessibleDescription(spoken(s));
            return label;
        }
    }

    @Override public AccessibleContext getAccessibleContext() {
        if(accessibleContext==null) accessibleContext=new GridAccessible();
        return accessibleContext;
    }

    /** One span said in words: "Recorded, Tuesday, 13:00 to 14:00". */
    private String spoken(Segment s) {
        String day=weekStart.plusDays(s.day()).getDayOfWeek()
            .getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.ENGLISH);
        String kind=s.repeating()?"Every "+day:s.planned()?"Planned, "+day
            :s.running()?"Running, "+day:"Recorded, "+day;
        return kind+", "+String.format("%02d:%02d",s.fromMinute()/60,s.fromMinute()%60)
            +" to "+String.format("%02d:%02d",s.toMinute()/60,s.toMinute()%60);
    }
}
