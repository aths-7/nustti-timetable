package edu.nustti.timetable.model;

/** 教务系统课表页可选学期项，如 value=2026-2027-1 / label=2026-2027学年第一学期。 */
public class TermOption {

    public String value = "";
    public String label = "";

    public TermOption() {
    }

    public TermOption(String value, String label) {
        this.value = value == null ? "" : value;
        this.label = label == null ? "" : label;
    }

    @Override
    public String toString() {
        return value + "(" + label + ")";
    }
}
