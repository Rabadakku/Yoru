package dev.yoru.ui;
import javax.swing.*;
import java.awt.*;
import java.text.ParseException;
import java.time.*;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Shared date and time picker.
 *
 * Invalid text cannot leave this component. The editors revert to their last
 * valid value rather than keeping what was typed, and {@link #value()} reads the
 * spinner model instead of the raw text, so a half-typed field can never reach
 * the domain. An earlier version read the text directly under PERSIST focus
 * behaviour, which is how "012:29" reached LocalTime.parse and threw.
 */
public final class DateTimeField extends JPanel {
    private static final Instant MIN=Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX=Instant.parse("2199-12-31T23:59:59Z");
    private final JSpinner date,time;
    private final ZoneId zone;

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
        super(new FlowLayout(FlowLayout.LEFT,Theme.SPACE_SM,Theme.SPACE_XS));
        setOpaque(false);
        this.zone=zone;
        Date start=Date.from(clamp(initial));
        date=spinner(start,Calendar.DAY_OF_MONTH,"yyyy-MM-dd",132);
        time=spinner(start,Calendar.MINUTE,"HH:mm:ss",112);
        add(date);
        add(time);
        add(Theme.button("Now",()->set(Instant.now())));
        getAccessibleContext().setAccessibleName(role==null?"Date and time in "+zone:role+" date and time in "+zone);
    }

    private JSpinner spinner(Date initial,int field,String pattern,int width) {
        var spinner=new JSpinner(new SpinnerDateModel(initial,Date.from(MIN),Date.from(MAX),field));
        var editor=new JSpinner.DateEditor(spinner,pattern);
        editor.getFormat().setTimeZone(TimeZone.getTimeZone(zone));
        editor.getFormat().setLenient(false);
        spinner.setEditor(editor);
        spinner.setPreferredSize(new Dimension(width,Theme.SPACE_XXL));
        spinner.setMaximumSize(new Dimension(width,Theme.SPACE_XXL));
        // Arrow chrome and a border that match every other control in the app.
        Theme.plainSpinner(spinner);
        var text=editor.getTextField();
        // DateEditor formats the initial value into the text field in its
        // constructor, before setTimeZone above could apply. The text was
        // therefore rendered in the JVM's zone while commitEdit parses it in the
        // vault's, so every value silently shifted by the offset between them
        // whenever the two differed. Re-render from the model, which is correct.
        text.setValue(spinner.getValue());
        text.setFont(Theme.labelFont());
        text.setToolTipText(pattern+" · "+zone);
        // Unparseable text snaps back on focus loss instead of persisting.
        text.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
        Theme.styleInput(text);
        return spinner;
    }

    /** Moves both editors to one instant, clamped into the supported range. */
    public void set(Instant when) {
        Date value=Date.from(clamp(when));
        date.setValue(value);
        time.setValue(value);
    }

    public Instant value() {
        return toInstant(LocalDateTime.of(committed(date).toLocalDate(),committed(time).toLocalTime()),zone);
    }

    /** Commits an in-progress edit, reverting the editor if it cannot be parsed. */
    private LocalDateTime committed(JSpinner spinner) {
        try {
            spinner.commitEdit();
        }
        catch(ParseException rejected) {
            // setValue on the field, not the spinner: it repaints the text from the model.
            ((JSpinner.DateEditor)spinner.getEditor()).getTextField().setValue(spinner.getValue());
        }
        return LocalDateTime.ofInstant(((Date)spinner.getValue()).toInstant(),zone);
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
