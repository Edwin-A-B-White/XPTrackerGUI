# XPTrackerGUI (Java Swing)

A lightweight desktop app that turns your study/coding/writing/workout minutes into **XP** with a **Level** and **progress bar**. You can add sessions manually or use a single-category **live timer** (Start/Pause, Stop & Add). Sessions can be **saved** to a plain text file and the **History** view compiles all-time totals per user.

> **Note:** This is a **Swing GUI**. Most online IDEs can’t open a desktop window in the browser—please run it locally.

---

##  Features

- **Manual Add**: pick a category and enter minutes (**1–300**).  
- **Live Timer**: single dropdown timer; **Start/Pause**, then **Stop & Add** to convert elapsed seconds → rounded minutes.  
- **All-Time Stats** (default): Level, total XP, % to next level, XP by category.  
- **History View**: type a username to compile totals from `xp_log.txt` (includes **unsaved** current session for the current user so the Level matches All-Time).  
- **Save on Exit**: asks to add timer minutes (if running) and/or save unsaved entries before closing.  
- **Strict Validation**: manual minutes must be a whole number **1–300**.

---

##  Requirements

- **Java 17+** (works on newer JDKs as well)

---

##  Run Locally

```bash
javac XPTrackerGUI.java
java  XPTrackerGUI
