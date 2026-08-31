const fields = {
  owner: ['fullName', 'email', 'phone', 'userId'],
  airline: ['iataCode', 'icaoCode', 'airlineName', 'alias', 'country', 'logoUrl', 'website', 'status', 'alliance', 'headquartersCity'],
  support: ['supportEmail', 'supportPhone', 'supportHours', 'additionalNotes'],
};

export function sanitizeOnboardingData(data) {
  return Object.fromEntries(Object.entries(fields).map(([section, keys]) => [section,
    Object.fromEntries(keys.filter((key) => ['string', 'number', 'boolean'].includes(typeof data?.[section]?.[key]))
      .map((key) => [key, data[section][key]])),
  ]));
}

export function readOnboardingProgress() {
  const empty = { currentStep: 1, formData: sanitizeOnboardingData() };
  const saved = localStorage.getItem('airline_onboarding_progress');
  if (!saved) return empty;
  try {
    const progress = JSON.parse(saved);
    const safe = {
      currentStep: Number.isInteger(progress.currentStep) && progress.currentStep >= 1 && progress.currentStep <= 4 ? progress.currentStep : 1,
      formData: sanitizeOnboardingData(progress.formData),
    };
    // Scrub credentials from progress saved by older versions before restoring it.
    localStorage.setItem('airline_onboarding_progress', JSON.stringify(safe));
    return localStorage.getItem('jwt') ? safe : empty;
  } catch {
    localStorage.removeItem('airline_onboarding_progress');
    return empty;
  }
}
