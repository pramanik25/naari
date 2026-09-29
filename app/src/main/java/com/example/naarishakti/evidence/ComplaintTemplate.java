package com.example.naarishakti.evidence;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.naarishakti.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Builds the formal complaint draft (to the SHO or to the NCW) in English or Hindi. All wording
 * comes from the ev_tpl_en_* / ev_tpl_hi_* resources; this class only assembles the sections.
 */
final class ComplaintTemplate {

    /** Complainant details read from the profile database; any field may be empty. */
    static final class Profile {
        String name = "";
        String father = "";
        String address = "";
        String pincode = "";
        String mobile = "";
        String occupation = "";
    }

    /** String resource ids for one language. */
    private static final class Res {
        @StringRes int toSho, toNcw, date, subjectSho, subjectNcw, salutation, intro;
        @StringRes int sec1, name, father, address, addressPin, mobile, occupation;
        @StringRes int sec2, incDate, incTimeRange, incTime, incPlace, incPlaceGps, incAlert;
        @StringRes int sec3, desc, sec4, evIntro, evNone, evItem, evVerified, kindPhoto, kindAudio, kindVideo;
        @StringRes int sec5, requestSho, requestNcw, closing, signDate, signPlace;
    }

    private static final Res EN = new Res();
    private static final Res HI = new Res();

    static {
        EN.toSho = R.string.ev_tpl_en_to_sho;
        EN.toNcw = R.string.ev_tpl_en_to_ncw;
        EN.date = R.string.ev_tpl_en_date;
        EN.subjectSho = R.string.ev_tpl_en_subject_sho;
        EN.subjectNcw = R.string.ev_tpl_en_subject_ncw;
        EN.salutation = R.string.ev_tpl_en_salutation;
        EN.intro = R.string.ev_tpl_en_intro;
        EN.sec1 = R.string.ev_tpl_en_sec1;
        EN.name = R.string.ev_tpl_en_name;
        EN.father = R.string.ev_tpl_en_father;
        EN.address = R.string.ev_tpl_en_address;
        EN.addressPin = R.string.ev_tpl_en_address_pin;
        EN.mobile = R.string.ev_tpl_en_mobile;
        EN.occupation = R.string.ev_tpl_en_occupation;
        EN.sec2 = R.string.ev_tpl_en_sec2;
        EN.incDate = R.string.ev_tpl_en_inc_date;
        EN.incTimeRange = R.string.ev_tpl_en_inc_time_range;
        EN.incTime = R.string.ev_tpl_en_inc_time;
        EN.incPlace = R.string.ev_tpl_en_inc_place;
        EN.incPlaceGps = R.string.ev_tpl_en_inc_place_gps;
        EN.incAlert = R.string.ev_tpl_en_inc_alert;
        EN.sec3 = R.string.ev_tpl_en_sec3;
        EN.desc = R.string.ev_tpl_en_desc;
        EN.sec4 = R.string.ev_tpl_en_sec4;
        EN.evIntro = R.string.ev_tpl_en_ev_intro;
        EN.evNone = R.string.ev_tpl_en_ev_none;
        EN.evItem = R.string.ev_tpl_en_ev_item;
        EN.evVerified = R.string.ev_tpl_en_ev_verified;
        EN.kindPhoto = R.string.ev_tpl_en_kind_photo;
        EN.kindAudio = R.string.ev_tpl_en_kind_audio;
        EN.kindVideo = R.string.ev_tpl_en_kind_video;
        EN.sec5 = R.string.ev_tpl_en_sec5;
        EN.requestSho = R.string.ev_tpl_en_request_sho;
        EN.requestNcw = R.string.ev_tpl_en_request_ncw;
        EN.closing = R.string.ev_tpl_en_closing;
        EN.signDate = R.string.ev_tpl_en_sign_date;
        EN.signPlace = R.string.ev_tpl_en_sign_place;

        HI.toSho = R.string.ev_tpl_hi_to_sho;
        HI.toNcw = R.string.ev_tpl_hi_to_ncw;
        HI.date = R.string.ev_tpl_hi_date;
        HI.subjectSho = R.string.ev_tpl_hi_subject_sho;
        HI.subjectNcw = R.string.ev_tpl_hi_subject_ncw;
        HI.salutation = R.string.ev_tpl_hi_salutation;
        HI.intro = R.string.ev_tpl_hi_intro;
        HI.sec1 = R.string.ev_tpl_hi_sec1;
        HI.name = R.string.ev_tpl_hi_name;
        HI.father = R.string.ev_tpl_hi_father;
        HI.address = R.string.ev_tpl_hi_address;
        HI.addressPin = R.string.ev_tpl_hi_address_pin;
        HI.mobile = R.string.ev_tpl_hi_mobile;
        HI.occupation = R.string.ev_tpl_hi_occupation;
        HI.sec2 = R.string.ev_tpl_hi_sec2;
        HI.incDate = R.string.ev_tpl_hi_inc_date;
        HI.incTimeRange = R.string.ev_tpl_hi_inc_time_range;
        HI.incTime = R.string.ev_tpl_hi_inc_time;
        HI.incPlace = R.string.ev_tpl_hi_inc_place;
        HI.incPlaceGps = R.string.ev_tpl_hi_inc_place_gps;
        HI.incAlert = R.string.ev_tpl_hi_inc_alert;
        HI.sec3 = R.string.ev_tpl_hi_sec3;
        HI.desc = R.string.ev_tpl_hi_desc;
        HI.sec4 = R.string.ev_tpl_hi_sec4;
        HI.evIntro = R.string.ev_tpl_hi_ev_intro;
        HI.evNone = R.string.ev_tpl_hi_ev_none;
        HI.evItem = R.string.ev_tpl_hi_ev_item;
        HI.evVerified = R.string.ev_tpl_hi_ev_verified;
        HI.kindPhoto = R.string.ev_tpl_hi_kind_photo;
        HI.kindAudio = R.string.ev_tpl_hi_kind_audio;
        HI.kindVideo = R.string.ev_tpl_hi_kind_video;
        HI.sec5 = R.string.ev_tpl_hi_sec5;
        HI.requestSho = R.string.ev_tpl_hi_request_sho;
        HI.requestNcw = R.string.ev_tpl_hi_request_ncw;
        HI.closing = R.string.ev_tpl_hi_closing;
        HI.signDate = R.string.ev_tpl_hi_sign_date;
        HI.signPlace = R.string.ev_tpl_hi_sign_place;
    }

    private ComplaintTemplate() {}

    static String build(Context c, boolean hindi, boolean ncw, Profile p,
                        @Nullable EvidenceStore.IncidentInfo info, List<EvidenceStore.Item> items) {
        Res r = hindi ? HI : EN;
        // Numeric dates/times keep Latin digits in both languages, as on official forms.
        SimpleDateFormat day = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
        String blank = c.getString(R.string.ev_tpl_blank);
        long now = System.currentTimeMillis();
        long started = info != null ? info.startedAt : 0;
        String incDay = started > 0 ? day.format(new Date(started)) : blank;

        String name = or(p.name, blank);
        String father = or(p.father, blank);
        String address = TextUtils.isEmpty(p.address) ? blank
                : TextUtils.isEmpty(p.pincode) ? p.address : c.getString(r.addressPin, p.address, p.pincode);
        String mobile = or(p.mobile, blank);

        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(ncw ? r.toNcw : r.toSho)).append("\n\n");
        sb.append(c.getString(r.date, day.format(new Date(now)))).append("\n\n");
        sb.append(c.getString(ncw ? r.subjectNcw : r.subjectSho, incDay)).append("\n\n");
        sb.append(c.getString(r.salutation)).append("\n\n");
        sb.append(c.getString(r.intro, name, father, address, mobile)).append("\n\n");

        sb.append(c.getString(r.sec1)).append('\n');
        sb.append(c.getString(r.name, name)).append('\n');
        sb.append(c.getString(r.father, father)).append('\n');
        sb.append(c.getString(r.address, address)).append('\n');
        sb.append(c.getString(r.mobile, mobile)).append('\n');
        sb.append(c.getString(r.occupation, or(p.occupation, blank))).append("\n\n");

        sb.append(c.getString(r.sec2)).append('\n');
        sb.append(c.getString(r.incDate, incDay)).append('\n');
        if (started > 0 && info.endedAt > started) {
            sb.append(c.getString(r.incTimeRange, hm.format(new Date(started)), hm.format(new Date(info.endedAt))));
        } else {
            sb.append(c.getString(r.incTime, started > 0 ? hm.format(new Date(started)) : blank));
        }
        sb.append('\n');
        if (info != null && info.hasLocation) {
            sb.append(c.getString(r.incPlaceGps,
                    String.format(Locale.US, "%.6f", info.lastLat),
                    String.format(Locale.US, "%.6f", info.lastLng)));
        } else {
            sb.append(c.getString(r.incPlace));
        }
        sb.append('\n');
        if (info != null && !info.inferred && started > 0) {
            sb.append(c.getString(r.incAlert, hm.format(new Date(started)))).append('\n');
        }
        sb.append('\n');

        sb.append(c.getString(r.sec3)).append('\n');
        sb.append(c.getString(r.desc)).append("\n\n");

        sb.append(c.getString(r.sec4)).append('\n');
        if (items.isEmpty()) {
            sb.append(c.getString(r.evNone)).append("\n\n");
        } else {
            sb.append(c.getString(r.evIntro)).append('\n');
            int n = 1;
            for (EvidenceStore.Item it : items) {
                int kind = EvidenceStore.KIND_AUDIO.equals(it.kind) ? r.kindAudio
                        : EvidenceStore.KIND_VIDEO.equals(it.kind) ? r.kindVideo : r.kindPhoto;
                sb.append(c.getString(r.evItem, n++, c.getString(kind),
                        full.format(new Date(it.capturedAt)), EvUi.size(c, it.size),
                        or(it.sha256, blank)));
                if (it.verified) sb.append(c.getString(r.evVerified));
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append(c.getString(r.sec5)).append('\n');
        sb.append(c.getString(ncw ? r.requestNcw : r.requestSho)).append("\n\n");

        sb.append(c.getString(r.closing)).append('\n');
        sb.append(name).append('\n');
        sb.append(c.getString(r.mobile, mobile)).append('\n');
        sb.append(c.getString(r.signDate, day.format(new Date(now)))).append('\n');
        sb.append(c.getString(r.signPlace));
        return sb.toString();
    }

    private static String or(@Nullable String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value.trim();
    }
}
