export const formatDateTime = (dateTime) => {
    // Flight definitions can have no dates; null would otherwise become the epoch.
    if (dateTime == null || (typeof dateTime === 'string' && dateTime.trim() === '')) {
      return 'Not available';
    }

    const date = new Date(dateTime);
    if (Number.isNaN(date.getTime())) {
      return 'Not available';
    }

    return date.toLocaleString('en-IN', {
      weekday: 'short',
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    })
  }
