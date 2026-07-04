package co.subpilot.utils;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

public final class PhoneUtils {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private PhoneUtils() {
    }

    public static String normalize(String phone, String defaultRegion) {
        try {
            Phonenumber.PhoneNumber number = PHONE_UTIL.parse(phone, defaultRegion);

            if (!PHONE_UTIL.isValidNumber(number)) {
                throw new IllegalArgumentException("Invalid phone number");
            }

            String e164 = PHONE_UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
            // Remove leading '+' and store digits only
            return e164.startsWith("+") ? e164.substring(1) : e164;

        } catch (NumberParseException e) {
            throw new IllegalArgumentException("Invalid phone number: " + phone, e);
        }
    }
}