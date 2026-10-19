package dev.uchar.regelspraak.toolchain.engine;

import java.util.Locale;

/**
 * The language of uchar's messages in this distribution: Dutch unless asked otherwise. uchar's frontend defaults to
 * Dutch and its Drools backend to English, so without a choice made here a user would read both languages in one
 * report. This is the one place that makes the choice for the process, through uchar's system property; a request
 * that asks for another language ({@link #ofAcceptLanguage}) is rendered in it per call.
 *
 * <p>The choice must precede the first uchar class that renders, because a KieBase embeds the fixed sentences of an
 * evaluation fault in the language of the moment it is first loaded; hence every entry point calls {@link #apply}
 * first, and {@link UcharBuild} does as well for the code that reaches it without one.
 */
public final class MessageLanguage {
    private MessageLanguage() {}

    public static final String DEFAULT = "nl";
    /** uchar's own switch ({@code Messages.PROPERTY}). */
    private static final String PROPERTY = "uchar.messages.locale";

    /** Fixes the process language: {@code language} when given, else what the system property already says (the
     *  launcher's {@code --language}), else {@value #DEFAULT}. */
    public static void apply(String language) {
        if (language != null) {
            check(language);
            System.setProperty(PROPERTY, language);
        } else if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, DEFAULT);
        }
    }

    /** The language of a {@code --language} value or an {@code Accept-Language} tag; {@code nl} and {@code en} only,
     *  as uchar's catalogues have no other. */
    public static void check(String language) {
        if (!language.equals("nl") && !language.equals("en"))
            throw new IllegalArgumentException("taal '" + language + "' is onbekend (nl of en)");
    }

    /** The first language of an {@code Accept-Language} header that uchar has a catalogue for, or null when the
     *  header is absent or names none (the process language then stays). Quality values are honoured only as the
     *  order of the header, which is what a browser sends them in. */
    public static Locale ofAcceptLanguage(String header) {
        if (header == null) return null;
        for (String part : header.split(",")) {
            String tag = part.split(";", 2)[0].trim();
            String language = Locale.forLanguageTag(tag).getLanguage();
            if (language.equals("nl") || language.equals("en")) return Locale.forLanguageTag(language);
        }
        return null;
    }
}
