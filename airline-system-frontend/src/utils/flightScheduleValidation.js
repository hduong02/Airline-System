import * as Yup from "yup";
import { isDateOnly, formatDateOnly } from "./dateOnly";

// Validation schema
export const createFlightScheduleSchema = (originalStartDate = null) => Yup.object().shape({
  flightId: Yup.string().required("Flight is required"),

  departureTime: Yup.string()
    .required("Departure time is required")
    .matches(
      /^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$/,
      "Invalid time format (HH:MM)"
    ),
  arrivalTime: Yup.string()
    .required("Arrival time is required")
    .matches(
      /^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$/,
      "Invalid time format (HH:MM)"
    ),
  recurrenceType: Yup.string()
    .required("Recurrence type is required")
    .oneOf(["DAILY", "WEEKLY", "CUSTOM"], "Invalid recurrence type"),
  operatingDays: Yup.array().when("recurrenceType", {
    is: "WEEKLY",
    then: (schema) =>
      schema.min(
        1,
        "At least one operating day is required for weekly schedule"
      ),
    otherwise: (schema) => schema,
  }),
  startDate: Yup.string()
    .required("Start date is required")
    .test("date", "Invalid start date", value => isDateOnly(value))
    .test("start", "Start date cannot be in the past", value =>
      value === originalStartDate || value >= formatDateOnly(new Date())),
  endDate: Yup.string()
    .required("End date is required")
    .test("date", "Invalid end date", value => isDateOnly(value))
    .test("end", "End date must be on or after start date", function(value) { return value >= this.parent.startDate; }),
});

