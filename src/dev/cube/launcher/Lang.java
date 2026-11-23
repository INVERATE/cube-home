package dev.cube.launcher;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** The language chosen in the settings: 0 = the TV's language, 1 = English, 2 = French. */
final class Lang {
    private Lang() { }

    /** A context whose texts (and dates) are in the chosen language. */
    static Context wrap(Context base) {
        int lang = base.getSharedPreferences("cubehome", Context.MODE_PRIVATE).getInt("lang", 0);
        if (lang == 0) {   // the TV's language (also undoes an earlier choice for dates)
            Locale.setDefault(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0));
            return base;
        }
        Locale l = lang == 1 ? Locale.ENGLISH : Locale.FRENCH;
        Locale.setDefault(l);   // dates use the default locale
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(l);
        return base.createConfigurationContext(conf);
    }
}
