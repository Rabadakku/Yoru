package dev.yoru.ui;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.temporal.TemporalAmount;
import static dev.yoru.ui.Theme.*;

/**
 * Shared date and time picker, written and read the way Notion does (#24).
 *
 * One box reads as a sentence, "Sep 14, 2026 | 11:00 AM". The date and the time
 * are each typed into directly, the calendar button picks a day, Up and Down step
 * a day or a quarter hour, and Now sets both. The text is its own example of
 * what the field reads, and the caption underneath gives a few more.
 *
 * Unreadable text never leaves this component, and is never quietly replaced
 * either: {@link #value()} refuses it with a message naming the part it could not
 * read, and the caption turns red, so Save cannot store a time other than the one
 * on screen. The spinner this replaces reverted such text silently, and an
 * earlier version read it straight into LocalTime.parse, which is how "012:29"
 * threw. Text left exactly as this field wrote it returns the exact instant the
 * field was given, seconds included, so looking at a value can never change it.
 */
public final class DateTimeField extends JPanel {
    private static final Instant MIN=Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX=Instant.parse("2199-12-31T23:59:59Z");
    static final String EXAMPLE_NOTE="e.g. Sep 14 or 9/14, and 9:30 PM or 21:30";
    private final DateField.HintField date=new DateField.HintField(DateText.DATE_EXAMPLE);
    private final DateField.HintField time=new DateField.HintField(DateText.TIME_EXAMPLE);
    private final JPanel box=new JPanel();
    private final JLabel note=label(EXAMPLE_NOTE,TYPE_CAPTION,MUTED);
    private final ZoneId zone;
    private final String role;
    /** What the field shows, exactly as it was given or last read. */
    private Instant exact;

    /**
     * A lone picker, with nothing beside it to be told apart from.
     *
     * Anything that comes in a start/end pair must use {@link #DateTimeField(Instant,ZoneId,String)}
     * instead: two fields named "Date and time in Europe/London" are the same
     * control to a screen reader, and neither says which end it is.
     */
    public DateTimeField(Instant initial,ZoneId zone) {
        this(initial,zone,null);
    }

    /** @param role "Start", "End" — the name that tells one half of a pair from the other. */
    public DateTimeField(Instant initial,ZoneId zone,String role) {
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
        setOpaque(false);
        this.zone=zone;
        this.role=role;
        DateField.inner(date,"datetime.date");
        DateField.inner(time,"datetime.time");
        DateField.fitDate(date);
        DateField.fitTime(time);
        date.getAccessibleContext().setAccessibleName(role==null?"Date":role+" date");
        time.getAccessibleContext().setAccessibleName(role==null?"Time":role+" time");
        date.setToolTipText("Type a date like "+DateText.DATE_EXAMPLE+" or 9/14 · "+zone);
        time.setToolTipText("Type a time like "+DateText.TIME_EXAMPLE+" or 21:30 · "+zone);
        DateField.keys(date,days->shift(Period.ofDays(days)),this::openCalendar);
        DateField.keys(time,quarters->shift(Duration.ofMinutes(15L*quarters)),this::openCalendar);
        DateField.tidiesItself(date,this::tidy);
        DateField.tidiesItself(time,this::tidy);

        box.setLayout(new BoxLayout(box,BoxLayout.X_AXIS));
        box.setOpaque(true);
        box.setBackground(PANEL);
        box.add(date);
        box.add(Box.createHorizontalStrut(SPACE_SM));
        box.add(divider());
        box.add(Box.createHorizontalStrut(SPACE_SM));
        box.add(time);
        box.add(DateField.calendarButton(this::openCalendar,role));
        box.setBorder(groupBorder(box,date,time));
        var now=button("Now",()->set(Instant.now()));
        now.setName("datetime.now");
        now.getAccessibleContext().setAccessibleName(role==null?"Now":role+": now");
        add(flushRow(box,now));
        note.setName("datetime.note");
        add(note);
        getAccessibleContext().setAccessibleName(role==null?"Date and time in "+zone:role+" date and time in "+zone);
        set(initial);
    }

    /** Shows one instant, clamped into the supported range. */
    public void set(Instant when) {
        exact=clamp(when);
        var local=LocalDateTime.ofInstant(exact,zone);
        date.setText(DateText.date(local.toLocalDate()));
        time.setText(DateText.time(local.toLocalTime()));
        settle();
    }

    /**
     * The instant on screen.
     *
     * @throws IllegalArgumentException when the date or time cannot be read, or
     *         names a clock time daylight saving skips or repeats
     */
    public Instant value() {
        var shown=LocalDateTime.ofInstant(exact,zone);
        boolean dateAsShown=date.getText().equals(DateText.date(shown.toLocalDate()));
        boolean timeAsShown=time.getText().equals(DateText.time(shown.toLocalTime()));
        if(dateAsShown&&timeAsShown) { settle(); return exact; }
        LocalDate day;
        LocalTime clock;
        try { day=dateAsShown?shown.toLocalDate():DateText.parseDate(date.getText(),LocalDate.now(zone)); }
        catch(IllegalArgumentException e) { throw refuse("date",e,"Couldn't read that date · try Sep 14 or 9/14"); }
        try { clock=timeAsShown?shown.toLocalTime():DateText.parseTime(time.getText()); }
        catch(IllegalArgumentException e) { throw refuse("time",e,"Couldn't read that time · try 9:30 PM or 21:30"); }
        Instant read;
        try { read=toInstant(LocalDateTime.of(day,clock),zone); }
        catch(IllegalArgumentException e) { throw refuse(null,e,"Daylight saving skips or repeats that time"); }
        exact=read;
        settle();
        return read;
    }

    private IllegalArgumentException refuse(String part,IllegalArgumentException cause,String shortly) {
        note.setText(shortly);
        note.setForeground(DANGER);
        String which=role==null
            ?(part==null?"":Character.toUpperCase(part.charAt(0))+part.substring(1)+": ")
            :role+(part==null?"":" "+part)+": ";
        return new IllegalArgumentException(which+cause.getMessage(),cause);
    }

    private void settle() {
        note.setText(EXAMPLE_NOTE);
        note.setForeground(MUTED);
    }

    /** Rewrites readable text the way Yoru writes it; unreadable text stays, with the caption saying why. */
    private void tidy() {
        try { set(value()); } catch(IllegalArgumentException leftAsTyped) { }
    }

    /** Steps in the vault's local time, so a day is a calendar day even across daylight saving. */
    private void shift(TemporalAmount amount) {
        Instant from;
        try { from=value(); } catch(IllegalArgumentException unreadable) { from=exact; }
        set(from.atZone(zone).plus(amount).toInstant());
    }

    private void openCalendar() {
        LocalDate selected;
        try { selected=DateText.parseDate(date.getText(),LocalDate.now(zone)); }
        catch(IllegalArgumentException unreadable) { selected=LocalDateTime.ofInstant(exact,zone).toLocalDate(); }
        DateField.popup(box,selected,LocalDate.now(zone),day->{
            date.setText(DateText.date(day));
            tidy();
            date.requestFocusInWindow();
        },null);
    }

    private static JComponent divider() {
        var line=new JPanel();
        line.setOpaque(true);
        line.setBackground(LINE);
        var size=new Dimension(HAIRLINE,SPACE_LG);
        line.setPreferredSize(size);
        line.setMinimumSize(size);
        line.setMaximumSize(size);
        return line;
    }

    /** Local time to instant, refusing the two clock readings a zone cannot resolve. */
    static Instant toInstant(LocalDateTime local,ZoneId zone) {
        var offsets=zone.getRules().getValidOffsets(local);
        if(offsets.size()==1) return local.toInstant(offsets.getFirst());
        if(offsets.isEmpty())
            throw new IllegalArgumentException("That clock time does not exist on this date — daylight saving skips it. Choose another time.");
        throw new IllegalArgumentException("That clock time happens twice on this date — daylight saving repeats it. Choose another time.");
    }

    private static Instant clamp(Instant value) {
        return value.isBefore(MIN)?MIN:value.isAfter(MAX)?MAX:value;
    }
}
