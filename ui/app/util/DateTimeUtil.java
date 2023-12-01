package util;

import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.DateTimeParseException;

public class DateTimeUtil {

	public static String calToString(GregorianCalendar gc, Locale locale){
		if(gc == null) return "";

		Date date = gc.getTime();
		DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
				.withLocale(locale);
		String result = formatter.format(gc.toZonedDateTime());
		//DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM,  locale);
		//String result = df.format(date);
		return result;
	}

	public static GregorianCalendar convertStringToCalendar(String date, String format, Locale locale) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format).withLocale(locale);
		if(date == null || date.isEmpty()){
			return null;
		}
		try {
			LocalDateTime localDateTime = LocalDateTime.parse(date, formatter);
			return GregorianCalendar.from(localDateTime.atZone(ZoneId.systemDefault()));
		}
		catch(DateTimeParseException e){
			Util.logger.debug(MessageFormat.format("DateFormatError Pattern:{0} Text:{1}", format, date));
		}
		return null;
		//SimpleDateFormat sdf = new SimpleDateFormat(format, locale);
		//return convertStringToCalendar(date, sdf);
	}
	public static GregorianCalendar convertStringToCalendar(String date, DateFormat sdf) {
		Date d;
		if(date == null || date.isEmpty()){
			return null;
		}
		try {
			d = sdf.parse(date);
			GregorianCalendar cal = new GregorianCalendar();
			cal.setTime(d);
			return cal;
		} catch (ParseException e) {
			Util.logger.debug(MessageFormat.format("DateFormatError Pattern:{0} Text:{1}", sdf, date));
		}
		return null;
	}


	public static GregorianCalendar convertStringToCalendar(String date, Locale locale) {
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM,  locale);

		GregorianCalendar result = convertStringToCalendar(date,df);
		if (result == null) {
			result = convertStringToCalendar(date, "yyyy-MM-dd'T'HH:mm:ss.SSSZ",  Locale.JAPAN);
		}
		if (result == null){
			result = convertStringToCalendar(date, "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
		}
		if (result == null) {
			result = convertStringToCalendar(date, "yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
		}
		if (result == null) {
			result = convertStringToCalendar(date, "yyyy:MM:dd HH:mm:ss z", Locale.JAPAN);
		}
		if (result == null) {
			result = convertStringToCalendar(date, "yyyy-MM-dd HH:mm:ss z", Locale.JAPAN);
		}
		if (result == null) {
			result = convertStringToCalendar(date, "yyyy-MM-dd HH:mm", Locale.JAPAN);
		}
		return result;
	}

}
