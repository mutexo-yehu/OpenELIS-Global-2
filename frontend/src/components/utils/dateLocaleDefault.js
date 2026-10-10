/**
 * The locale for dates formatted without one.
 *
 * Many screens print dates with `date.toLocaleString()` and similar calls that
 * name no locale (or `[]` / `navigator.language`), so they follow the browser:
 * on an en-US browser that is MM/DD/YYYY, while the date pickers and server
 * reports follow the site's DEFAULT_DATE_LOCALE. This makes such calls use
 * the site's date order instead. Calls that name a locale are left alone.
 */

const methods = ["toLocaleString", "toLocaleDateString", "toLocaleTimeString"];
// no prototype: a plain {} already has a toLocaleString of its own
const originals = Object.create(null);

/**
 * The locale to format dates with: the site's date locale, except that a
 * day-first date locale with an English interface (fr-FR dates, en-US
 * language) becomes en-GB, so dates read DD/MM/YYYY with English month names.
 */
export function dateFormattingLocale(dateLocale, languageLocale) {
  if (!dateLocale) {
    return undefined;
  }
  const dayFirst = !dateLocale.toLowerCase().startsWith("en-us");
  if (dayFirst && (languageLocale || "").toLowerCase().startsWith("en")) {
    return "en-GB";
  }
  return dateLocale;
}

function namesNoLocale(locales) {
  return (
    locales === undefined ||
    locales === null ||
    (Array.isArray(locales) && locales.length === 0) ||
    (typeof navigator !== "undefined" && locales === navigator.language)
  );
}

/** Use `locale` for dates formatted without a locale; undefined restores the default. */
export function installDateLocaleDefault(locale) {
  methods.forEach((method) => {
    if (!originals[method]) {
      originals[method] = Date.prototype[method];
    }
    const original = originals[method];
    Date.prototype[method] = locale
      ? function (locales, options) {
          return original.call(
            this,
            namesNoLocale(locales) ? locale : locales,
            options,
          );
        }
      : original;
  });
}
