// XPTrackerGUI
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XPTrackerGUI {

    //                                                                                         Data Model (one log entry) 
    // Represents a single activity session that was recorded by the user.
    static class ActivityEntry {
        String date;     // date string like "2025-09-04"
        String category; // STUDY, CODING, WORKOUT, or WRITING
        int minutes;     // minutes logged for this entry (validated 1-300 per chunk)
        int xp;          // computed XP for this entry: minutes * category multiplier
        String user;     // which user this entry belongs to (from the startup username prompt)
        ActivityEntry(String date, String category, int minutes, int xp, String user) {
            this.date = date; this.category = category; this.minutes = minutes; this.xp = xp; this.user = user;
        }
        String toCSV() { return date + "," + category + "," + minutes + "," + xp; } // saved line format
    }

    //                                                                                 Session totals (in memory for current run only) 
    // multiplier: XP/minute for each category 
    private final Map<String,Integer> multiplier = new LinkedHashMap<>();
    private final List<ActivityEntry> log = new ArrayList<>();                 // entries created in the current run
    private final Map<String,Integer> xpByCategory = new HashMap<>();          // current run XP per category
    private int totalXP = 0;                                                   // current run total XP
    private String userName = "Player";                                        // from the startup username prompt

    //                                                                                Baseline history (loaded from file for this user at startup) 
    // These are the saved totals from previous sessions; we add the current-run totals to get "All-Time".
    private final Map<String,Integer> histXpByCategory = new HashMap<>();
    private int histTotalXP = 0;
    private int histEntries = 0;

    //                                                                   UI components (fields so event handlers can reach them)
    private JFrame frame;
    private JComboBox<String> categoryBox;           // manual add: category picker
    private JSpinner minutesSpinner;                 // manual add: minutes input (validation handled by parsing text due to issue with with JSpinner validation)
    private JTextArea statsArea, historyArea;        // displays All-Time stats and compiled history text
    private JLabel levelLabel, totalXPLabel;         // summary labels above the stats
    private JProgressBar progressBar;                // visual progress toward next level
    private JTextField historyUserField;             // text field to search history for a username

    //                                                              Single live timer (prevents mulitiple timers running simultaneously)
    private JComboBox<String> timerCategoryBox;      // category for the timer (locked while running)
    private JLabel timerTimeLabel;                   // 00:00:00 display
    private JButton timerStartPauseBtn;              // toggles start/pause
    private JButton timerStopAddBtn;                 // stops and adds rounded minutes
    private javax.swing.Timer timerTick;             // ticks every second on the EDT
    private boolean timerRunning = false;            // timer state flag
    private long timerElapsedSec = 0;                // elapsed seconds for the current timer run

    //                                                                               Validation bounds for minutes
    private static final int MIN_MIN = 1;            // user must enter at least 1 minute
    private static final int MAX_MIN = 300;          // one chunk cannot exceed 300 minutes

    //                                                                  Convert seconds to "hh:mm:ss" for the timer label.
    private static String hms(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    //                                                                                   Program entry point
    public static void main(String[] args) {
        try {
            // Try to use Nimbus look & feel (nicer GUI), else fall back to system default (not all systems have Nimbus).
            boolean set = false;
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) { UIManager.setLookAndFeel(info.getClassName()); set = true; break; }
            }
            if (!set) UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {}

        // Swing must run on the Event Dispatch Thread (EDT); we build the GUI there.
        SwingUtilities.invokeLater(() -> new XPTrackerGUI().start());
    }

    //                                                           Initial startup: get name, set multipliers, load baseline, build UI, show stats
    private void start() {
        // Prompt the user for a name (used in file headers and to filter their history).
        String name = JOptionPane.showInputDialog(null, "Enter your name:", "XP Tracker", JOptionPane.QUESTION_MESSAGE);
        if (name != null && !name.trim().isEmpty()) userName = name.trim();

        // Category XP-per-minute multipliers (you can adjust these to change leveling speed and catagory weight).
        multiplier.put("STUDY", 5);
        multiplier.put("CODING", 6);
        multiplier.put("WORKOUT", 8);
        multiplier.put("WRITING", 4);

        // Load saved history for this user so All-Time stats = history + current run.
        loadUserHistoryBaseline();

        // Build UI, keyboard shortcuts, and render initial stats.
        buildUI();
        installShortcuts();
        updateStatsArea();

        // Prefill History with current user's all-time view so Level matches immediately (current level in history and Log & stats must match).
        if (historyUserField != null) {
            historyUserField.setText(userName);
            viewHistoryByUser();
        }
    }

    //                                                                  Create the main window, tabs, and register an exit handler
        frame = new JFrame("XP Tracker — " + userName);
        // Manage closing to support "Save before exit?" and "Add timer minutes?" prompts.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); 
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { attemptExit(); }
        });
        frame.setSize(900, 660);
        frame.setLocationRelativeTo(null); // center on screen

        JPanel root = new JPanel(new BorderLayout(12,12));
        root.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));
        frame.setContentPane(root);

        // Two tabs: "Log & Stats" (add time, current stats) and "History" (review totals).
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Log & Stats", buildLogStatsPanel());
        tabs.addTab("History", buildHistoryPanel());
        root.add(tabs, BorderLayout.CENTER);

        frame.setVisible(true);
    }

    //                                                                 "Log & Stats" tab: manual add, single timer, and stats panel
    private JPanel buildLogStatsPanel() {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));

        JPanel controlsCard = cardPanel("Add Activity (Manual)");
        controlsCard.add(buildControlsRow(), BorderLayout.CENTER);

        JPanel timerCard = cardPanel("Live Timer (Single, by Category)");
        timerCard.add(buildSingleTimerPanel(), BorderLayout.CENTER);

        JPanel statsCard = cardPanel("All-Time Stats (History + This Session)");
        statsCard.add(buildStatsArea(), BorderLayout.CENTER);

        page.add(controlsCard); page.add(Box.createVerticalStrut(10));
        page.add(timerCard);    page.add(Box.createVerticalStrut(10));
        page.add(statsCard);

        // Wrapper keeps content compact at the top.
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(page, BorderLayout.NORTH); 
        return wrapper;
    }

    //                                                                  History tab: choose a username and show compiled totals 
        JPanel panel = new JPanel(new BorderLayout(12,12));
        JPanel historyCard = cardPanel("View Compiled History");

        // Top row: Username to view:, text field, View History button
        JPanel top = new JPanel(new BorderLayout(8,8));
        historyUserField = new JTextField(userName, 18);
        historyUserField.setToolTipText("Type a username to view all-time history from xp_log.txt (includes current session if it's your name)");
        JButton viewHistoryBtn = primaryButton("View History", e -> viewHistoryByUser());
        top.add(new JLabel("Username to view:"), BorderLayout.WEST);
        top.add(historyUserField, BorderLayout.CENTER);
        top.add(viewHistoryBtn, BorderLayout.EAST);

        // Big read-only area that shows totals, level, % to next level, and recent entries.
        historyArea = new JTextArea(20, 70);
        historyArea.setEditable(false);
        historyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        historyArea.setBorder(new EmptyBorder(6,6,6,6));

        historyCard.add(top, BorderLayout.NORTH);
        historyCard.add(new JScrollPane(historyArea), BorderLayout.CENTER);

        panel.add(historyCard, BorderLayout.CENTER);
        return panel;
    }

    //                                                               Manual Add row: category, minutes, Add, Save (with strict validation) 
        JPanel controls = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6,6,6,6);
        gc.fill = GridBagConstraints.HORIZONTAL;

        // Label + category dropdown
        JLabel categoryLabel = new JLabel("Category:");
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        controls.add(categoryLabel, gc);

        gc.gridx = 1; gc.weightx = 0.2;
        categoryBox = new JComboBox<>(multiplier.keySet().toArray(new String[0]));
        categoryBox.setToolTipText("STUDY, CODING, WORKOUT, WRITING");
        controls.add(categoryBox, gc);

        // Label + minutes spinner 
        JLabel minutesLabel = new JLabel("Minutes:");
        gc.gridx = 2; gc.weightx = 0;
        controls.add(minutesLabel, gc);

        gc.gridx = 3; gc.weightx = 0.2;
        minutesSpinner = new JSpinner(new SpinnerNumberModel(30, MIN_MIN, MAX_MIN, 5));
        minutesSpinner.setToolTipText("Enter minutes between " + MIN_MIN + " and " + MAX_MIN);
        minutesSpinner.setEditor(new JSpinner.NumberEditor(minutesSpinner, "#"));

        // Parse raw text because JSpinner reverts to last valid value on bad input.
        JFormattedTextField tf = ((JSpinner.NumberEditor) minutesSpinner.getEditor()).getTextField();
        tf.setInputVerifier(new InputVerifier() {
            @Override public boolean verify(JComponent c) {
                return readMinutesFromField(false) != null; // gate focus; no popup here
            }
        });
        // When focus actually leaves the field (select history tab), show the error dialog if needed.
        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { readMinutesFromField(true); }
        });

        controls.add(minutesSpinner, gc);

        // Add Activity button, validates and adds one entry
        gc.gridx = 4; gc.weightx = 0.3;
        JButton addBtn = primaryButton("Add Activity  (Ctrl+A)", e -> handleAddActivity());
        addBtn.setToolTipText("Add this session to the current log");
        controls.add(addBtn, gc);

        // Save Log, appends a session to xp_log.txt
        gc.gridx = 5; gc.weightx = 0.15;
        JButton saveBtn = new JButton("Save Log  (Ctrl+S)");
        saveBtn.addActionListener(e -> saveLog());
        saveBtn.setToolTipText("Append this session to xp_log.txt");
        controls.add(saveBtn, gc);

        return controls;
    }

    //                                                                 Single-category live timer row (Start/Pause, Stop & Add)
    private JPanel buildSingleTimerPanel() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6,6,6,6);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lbl = new JLabel("Timer Category:");
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        row.add(lbl, gc);

        timerCategoryBox = new JComboBox<>(multiplier.keySet().toArray(new String[0]));
        timerCategoryBox.setToolTipText("Select the category you want to time");
        gc.gridx = 1; gc.weightx = 0.25;
        row.add(timerCategoryBox, gc);

        // '00:00:00' elapsed time label
        timerTimeLabel = new JLabel("00:00:00");
        timerTimeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        gc.gridx = 2; gc.weightx = 0.15;
        row.add(timerTimeLabel, gc);

        // Start or Pause the ticking 
        timerStartPauseBtn = primaryButton("Start", e -> onTimerStartPause());
        gc.gridx = 3; gc.weightx = 0.2;
        row.add(timerStartPauseBtn, gc);

        // Stop & Add converts elapsed seconds to rounded minutes and adds an entry
        timerStopAddBtn = new JButton("Stop & Add");
        timerStopAddBtn.addActionListener(e -> onTimerStopAndAdd());
        timerStopAddBtn.setToolTipText("Stop the timer and add rounded minutes to the selected category");
        gc.gridx = 4; gc.weightx = 0.2;
        row.add(timerStopAddBtn, gc);

        // Swing timer ticks once per second on the EDT
        timerTick = new javax.swing.Timer(1000, e -> {
            if (timerRunning) {
                timerElapsedSec++;
                timerTimeLabel.setText(hms(timerElapsedSec));
            }
        });
        timerTick.setRepeats(true);

        return row;
    }

    //                                                                       Stats panel (Level, Total XP, progress bar, and breakdown text)
    private JPanel buildStatsArea() {
        JPanel panel = new JPanel(new BorderLayout(8,8));

        JPanel header = new JPanel(new GridLayout(1,3,8,8));
        levelLabel = new JLabel("Level: 1");
        levelLabel.setFont(levelLabel.getFont().deriveFont(Font.BOLD, 14f));
        totalXPLabel = new JLabel("Total XP: 0");
        totalXPLabel.setFont(totalXPLabel.getFont().deriveFont(Font.BOLD, 14f));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setForeground(new Color(52, 152, 219));
        header.add(levelLabel);
        header.add(totalXPLabel);
        header.add(progressBar);

        statsArea = new JTextArea(12, 70);
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        statsArea.setBorder(new EmptyBorder(6,6,6,6));

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(statsArea), BorderLayout.CENTER);
        return panel;
    }

    //                                                                                 Styled helper so sections look consistent
    private JPanel cardPanel(String title) {
        JPanel card = new JPanel(new BorderLayout(10,10));
        card.setOpaque(true);
        card.setBackground(new Color(250, 250, 252));
        Border outer = new LineBorder(new Color(225, 228, 235));
        Border inner = new EmptyBorder(10,12,12,12);
        TitledBorder titleBorder = BorderFactory.createTitledBorder(outer, title, TitledBorder.LEFT, TitledBorder.TOP);
        titleBorder.setTitleFont(new Font("SansSerif", Font.BOLD, 13));
        card.setBorder(new CompoundBorder(titleBorder, inner));
        return card;
    }

    //                                                                          Timer: Start/Pause toggle (locks category while timing)
    private void onTimerStartPause() {
        if (!timerRunning) {
            timerRunning = true;
            timerTick.start();
            timerStartPauseBtn.setText("Pause");
            timerCategoryBox.setEnabled(false); // prevent mid-run category changes
        } else {
            timerRunning = false;
            timerTick.stop();
            timerStartPauseBtn.setText("Start");
            // keep category locked until Stop & Add or resume (so timing remains honest)
        }
    }

    //                                                                       Timer: Stop & Add (round seconds to minutes; add if ≥1; reset UI)
    private void onTimerStopAndAdd() {
        if (timerRunning) {
            timerRunning = false;
            timerTick.stop();
            timerStartPauseBtn.setText("Start");
        }
        int minutes = (int) Math.round(timerElapsedSec / 60.0);
        if (minutes < 1) {
            JOptionPane.showMessageDialog(frame,
                    "Timer is less than 1 minute. Nothing added.",
                    "Timer", JOptionPane.INFORMATION_MESSAGE);
        } else {
            String cat = (String) timerCategoryBox.getSelectedItem();
            addMinutesFromTimer(cat, minutes); // chunk + stats update
            JOptionPane.showMessageDialog(frame,
                    "Added: " + minutes + " minute(s) of " + cat + " for " + (minutes * multiplier.get(cat)) + " XP.",
                    "Timer Added",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        // Reset the timer display and unlock the category dropdown for the next run.
        timerElapsedSec = 0;
        timerTimeLabel.setText("00:00:00");
        timerCategoryBox.setEnabled(true);
    }

    //                                                                       Manual Add handler: validate minutes, add entry, confirm
    private void handleAddActivity() {
        Integer minutes = readMinutesFromField(true); // strict parsing of raw text, show error if bad
        if (minutes == null) return;

        String category = (String) categoryBox.getSelectedItem();
        addMinutesFromTimer(category, minutes); // uses chunking + totals update

        JOptionPane.showMessageDialog(frame,
                "Added: " + minutes + " minute(s) of " + category + " for " + (minutes * multiplier.get(category)) + " XP.",
                "Activity Added",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     Strict manual-minutes validation:
     Reads the spinner's raw text (digits only), not spinner.getValue() (avoids last valid value issue).
     Enforces 1 to 300.
     If valid, syncs the spinner to the accepted number and returns it; else shows an error and returns null.
     */
    private Integer readMinutesFromField(boolean showError) {
        JFormattedTextField tf = ((JSpinner.NumberEditor) minutesSpinner.getEditor()).getTextField();
        String raw = tf.getText().trim();
        if (raw.isEmpty()) {
            if (showError) showMinutesError("Enter minutes between " + MIN_MIN + " and " + MAX_MIN + ".");
            return null;
        }
        int val;
        try {
            // Only whole numbers are allowed
            val = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            if (showError) showMinutesError("Minutes must be a whole number between " + MIN_MIN + " and " + MAX_MIN + ".");
            return null;
        }
        if (val < MIN_MIN || val > MAX_MIN) {
            if (showError) showMinutesError("Minutes must be between " + MIN_MIN + " and " + MAX_MIN + ".");
            return null;
        }
        // Keep the spinner visually in sync with what we accepted.
        try { minutesSpinner.setValue(val); } catch (IllegalArgumentException ignore) {}
        return val;
    }

    //                                                         Add minutes helper: chunk >300 into legal pieces for computing XP; refresh stats
    private void addMinutesFromTimer(String category, int minutes) {
        while (minutes > 0) {
            int chunk = Math.min(minutes, MAX_MIN); // one persisted entry is at most 300 minutes
            int xp = chunk * multiplier.get(category);
            String date = LocalDate.now().toString();
            ActivityEntry entry = new ActivityEntry(date, category, chunk, xp, userName);
            log.add(entry);
            totalXP += xp;
            xpByCategory.merge(category, xp, Integer::sum); // increment per-category XP
            minutes -= chunk; // consume this chunk and continue if anything remains
        }
        updateStatsArea();
    }

    //                                                Friendly error UX for minutes input: beep, brief highlight, and dialog
        Toolkit.getDefaultToolkit().beep();
        JFormattedTextField tf = ((JSpinner.NumberEditor) minutesSpinner.getEditor()).getTextField();
        Color old = tf.getBackground();
        tf.setBackground(new Color(255, 235, 238)); // light red highlight

        // Briefly flash, then restore background (Swing Timer runs on EDT).
        javax.swing.Timer flash = new javax.swing.Timer(850, e -> {
            tf.setBackground(old);
            ((javax.swing.Timer) e.getSource()).stop();
        });
        flash.setRepeats(false);
        flash.start();

        JOptionPane.showMessageDialog(frame, msg, "Invalid Minutes", JOptionPane.ERROR_MESSAGE);
        tf.requestFocusInWindow();
        tf.selectAll(); // make it easy to retype
    }

    //                                                                Persist current session to xp_log.txt (header + CSV lines)
    private void saveLog() {
        if (log.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Nothing to save yet.", "Save Log", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File file = new File("xp_log.txt");
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            // Header marks which user and when this session was saved.
            out.println("=== Session for " + userName + " on " + LocalDateTime.now() + " ===");
            for (ActivityEntry e : log) out.println(e.toCSV()); // one CSV line per entry
            out.println(); // blank line to separate sessions
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Error saving log: " + ex.getMessage(),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(frame,
                "Saved " + log.size() + " entries to:\n" + new File("xp_log.txt").getAbsolutePath(),
                "Saved", JOptionPane.INFORMATION_MESSAGE);
    }

    //                                                  Compile totals for any username; if it's the current user, merge unsaved current session
    private void viewHistoryByUser() {
        String filter = historyUserField.getText().trim();
        if (filter.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Enter a username to view.", "History", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Read all saved entries and compile only those matching the requested username (case insensitive).
        List<ActivityEntry> all = loadHistory(new File("xp_log.txt"));
        int total = 0, count = 0;
        Map<String,Integer> byCat = new HashMap<>();
        List<ActivityEntry> recent = new ArrayList<>();
        for (ActivityEntry e : all) {
            if (e.user != null && e.user.equalsIgnoreCase(filter)) {
                total += e.xp; count++;
                byCat.merge(e.category, e.xp, Integer::sum);
                recent.add(e);
            }
        }
        // Ensure the History view shows the same level as the All-Time panel for the current user.
        if (filter.equalsIgnoreCase(userName) && !log.isEmpty()) {
            for (ActivityEntry e : log) {
                total += e.xp; count++;
                byCat.merge(e.category, e.xp, Integer::sum);
                recent.add(e);
            }
        }

        if (count == 0) {
            historyArea.setText("No entries found for user: " + filter);
            return;
        }

        int level = (total / 1000) + 1;
        int xpIntoLevel = total % 1000;
        double pct = (total == 0) ? 0.0 : (xpIntoLevel / 1000.0);
        DecimalFormat df = new DecimalFormat("0%");

        StringBuilder sb = new StringBuilder();
        sb.append("--- History for ").append(filter).append(" ---\n");
        if (filter.equalsIgnoreCase(userName) && !log.isEmpty()) {
            sb.append("(includes unsaved entries from this session)\n");
        }
        sb.append("Entries recorded: ").append(count).append("\n");
        sb.append("Total XP: ").append(total).append("\n");
        sb.append("Level: ").append(level)
          .append("  (").append(xpIntoLevel).append("/1000 to next level, ").append(df.format(pct)).append(")\n\n");

        sb.append("XP by category:\n");
        for (Map.Entry<String,Integer> e : byCat.entrySet()) {
            sb.append(String.format("  %-8s : %d%n", e.getKey(), e.getValue()));
        }

        sb.append("\nMost recent entries:\n");
        for (int i = recent.size() - 1, shown = 0; i >= 0 && shown < 10; i--, shown++) {
            ActivityEntry e = recent.get(i);
            sb.append("  ").append(e.date).append("  ")
              .append(e.category).append("  ").append(e.minutes).append("m  ")
              .append(e.xp).append(" XP\n");
        }
        historyArea.setText(sb.toString());
    }

    //                                                             Graceful exit: optionally add timer minutes; optionally save; then close
    private void attemptExit() {
        // If a timer is running, offer to add the rounded minutes to the selected category before exiting.
        if (timerRunning) {
            timerRunning = false;
            timerTick.stop();
            timerStartPauseBtn.setText("Start");
            int add = JOptionPane.showConfirmDialog(
                    frame,
                    "A timer is running.\nAdd the rounded minutes (" +
                    (int)Math.round(timerElapsedSec/60.0) + " min) to \"" +
                    timerCategoryBox.getSelectedItem() + "\" before exiting?",
                    "Timer Running",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (add == JOptionPane.CANCEL_OPTION || add == JOptionPane.CLOSED_OPTION) {
                // User changed their mind, stay in the app.
                return;
            }
            if (add == JOptionPane.YES_OPTION) {
                int minutes = (int)Math.round(timerElapsedSec/60.0);
                if (minutes >= 1) {
                    addMinutesFromTimer((String)timerCategoryBox.getSelectedItem(), minutes);
                }
            }
            // Reset timer UI
            timerElapsedSec = 0;
            timerTimeLabel.setText("00:00:00");
            timerCategoryBox.setEnabled(true);
        }

        // If nothing new to save, just confirm exit.
        if (log.isEmpty()) {
            int r = JOptionPane.showConfirmDialog(
                    frame,
                    "No new entries to save. Exit now?",
                    "Exit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (r == JOptionPane.YES_OPTION) {
                frame.dispose();
                System.exit(0);
            }
            return;
        }

        // Otherwise, offer to save before exit (Yes/No/Cancel).
        int res = JOptionPane.showConfirmDialog(
                frame,
                "Save your current session before exit?",
                "Save before exit",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (res == JOptionPane.CANCEL_OPTION || res == JOptionPane.CLOSED_OPTION) return;
        if (res == JOptionPane.YES_OPTION) {
            saveLog(); // If saving fails, an error dialog is shown; we still proceed to close.
        }
        frame.dispose();
        System.exit(0);
    }

    //                                                                    Recalculate and render All-Time stats (history + this session)
    private void updateStatsArea() {
        // All-Time totals are the sum of saved baseline + current run entries.
        int allTotal = histTotalXP + totalXP;
        int level = (allTotal / 1000) + 1;
        int xpIntoLevel = allTotal % 1000;
        double pct = (allTotal == 0) ? 0.0 : (xpIntoLevel / 1000.0);
        DecimalFormat df = new DecimalFormat("0%");

        levelLabel.setText("Level: " + level + "  (" + xpIntoLevel + "/1000 to next)");
        totalXPLabel.setText("Total XP: " + allTotal);
        progressBar.setValue((int) Math.round(pct * 100));
        progressBar.setString(df.format(pct));

        // Merge per category XP: start with history, then add current run values.
        Map<String,Integer> allByCat = new LinkedHashMap<>(histXpByCategory);
        for (Map.Entry<String,Integer> e : xpByCategory.entrySet()) {
            allByCat.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        // Build a readable breakdown in the text area.
        StringBuilder sb = new StringBuilder();
        sb.append("Entries (all-time): ").append(histEntries + log.size()).append("\n");
        sb.append("XP by category (all-time):\n");
        if (allByCat.isEmpty()) {
            sb.append("  (no entries yet)\n");
        } else {
            for (Map.Entry<String,Integer> e : allByCat.entrySet()) {
                sb.append(String.format("  %-8s : %d%n", e.getKey(), e.getValue()));
            }
        }
        sb.append("\nThis session only:\n");
        sb.append("  Entries: ").append(log.size()).append("\n");
        if (xpByCategory.isEmpty()) {
            sb.append("  XP by category: (none yet)\n");
        } else {
            for (Map.Entry<String,Integer> e : xpByCategory.entrySet()) {
                sb.append(String.format("  %-8s : %d%n", e.getKey(), e.getValue()));
            }
        }
        statsArea.setText(sb.toString());
    }

    //                                                                             Load saved totals for this user so All-Time = history + session
    private void loadUserHistoryBaseline() {
        List<ActivityEntry> all = loadHistory(new File("xp_log.txt"));
        for (ActivityEntry e : all) {
            if (e.user != null && e.user.equalsIgnoreCase(userName)) {
                histTotalXP += e.xp;
                histEntries += 1;
                histXpByCategory.merge(e.category, e.xp, Integer::sum);
            }
        }
    }

    //                                                                Parse xp_log.txt: headers identify the user; following CSV lines that belong to that user 
    private List<ActivityEntry> loadHistory(File file) {
        List<ActivityEntry> all = new ArrayList<>();
        if (!file.exists()) return all;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line, currentUser = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("=== Session for ")) {
                    // Extract the user between "=== Session for " and " on "
                    int start = "=== Session for ".length();
                    int onIdx = line.indexOf(" on ", start);
                    currentUser = (onIdx > start) ? line.substring(start, onIdx).trim() : null;
                } else if (!line.trim().isEmpty()) {
                    // Expect CSV lines: date,category,minutes,xp
                    String[] p = line.split(",");
                    if (p.length == 4 && currentUser != null) {
                        try {
                            String date = p[0].trim();
                            String category = p[1].trim();
                            int minutes = Integer.parseInt(p[2].trim());
                            int xp = Integer.parseInt(p[3].trim());
                            all.add(new ActivityEntry(date, category, minutes, xp, currentUser));
                        } catch (NumberFormatException ignore) {
                            // Skip malformed numeric lines but continue parsing.
                        }
                    }
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Error reading history: " + ex.getMessage(),
                    "History Error", JOptionPane.ERROR_MESSAGE);
        }
        return all;
    }

    //                                                                                   Small helper to make a consistent "primary" button look 
    private JButton primaryButton(String text, ActionListener action) {
        JButton b = new JButton(text);
        b.addActionListener(action);
        b.setBackground(new Color(35, 134, 54));
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setBorder(new CompoundBorder(new LineBorder(new Color(28, 106, 43)), new EmptyBorder(6,10,6,10)));
        b.setFocusPainted(false);
        return b;
    }

    //                                                                          Cross-version shortcut mask via reflection (avoids any compile-time deprecation warnings)
    private int menuShortcutMask() {
        try {
            return (int) Toolkit.class
                .getMethod("getMenuShortcutKeyMaskEx")
                .invoke(Toolkit.getDefaultToolkit()); // Java 9+
        } catch (Exception ignore) {
            try {
                return (int) Toolkit.class
                    .getMethod("getMenuShortcutKeyMask")
                    .invoke(Toolkit.getDefaultToolkit()); // Java 8 
            } catch (Exception e) {
                return InputEvent.META_DOWN_MASK; // macOS fallback (my device)
            }
        }
    }

    //                                                                             Global hotkeys: Ctrl/Cmd+A (Add), Ctrl/Cmd+S (Save), Ctrl/Cmd+H (History)
    private void installShortcuts() {
        int mask = menuShortcutMask();
        JRootPane root = frame.getRootPane();
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), "add");
        am.put("add", new AbstractAction() { public void actionPerformed(ActionEvent e) { handleAddActivity(); } });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "save");
        am.put("save", new AbstractAction() { public void actionPerformed(ActionEvent e) { saveLog(); } });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, mask), "history");
        am.put("history", new AbstractAction() { public void actionPerformed(ActionEvent e) { viewHistoryByUser(); } });
    }
}
