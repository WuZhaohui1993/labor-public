import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import timezone from "dayjs/plugin/timezone";

dayjs.extend(utc);
dayjs.extend(timezone);

const DISPLAY_TIME_ZONE = "Asia/Shanghai";

type DateValue = string | number | Date | null | undefined;

function parseDate(value: DateValue) {
  if (value === null || value === undefined || value === "") return;
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.tz(DISPLAY_TIME_ZONE) : undefined;
}

export function formatDateTime(value: DateValue, emptyText = "—") {
  return parseDate(value)?.format("YYYY-MM-DD HH:mm:ss") || emptyText;
}

export function formatDate(value: DateValue, emptyText = "—") {
  return parseDate(value)?.format("YYYY-MM-DD") || emptyText;
}
