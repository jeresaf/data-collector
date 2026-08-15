export interface OdkGeoPoint {
  latitude: number
  longitude: number
  altitude: number
  accuracy: number
}

const NUMBER = /^[-+]?(?:\d+(?:\.\d*)?|\.\d+)$/

function finiteNumber(value: string): number | null {
  const trimmed = value.trim()
  if (!NUMBER.test(trimmed)) return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

export function parseGeoPoint(value: string): OdkGeoPoint | null {
  const parts = value.trim().split(/\s+/).filter(Boolean)
  if (parts.length < 2 || parts.length > 4) return null

  const latitude = finiteNumber(parts[0])
  const longitude = finiteNumber(parts[1])
  const altitude = parts.length >= 3 ? finiteNumber(parts[2]) : 0
  const accuracy = parts.length >= 4 ? finiteNumber(parts[3]) : 0

  if (latitude == null || longitude == null || altitude == null || accuracy == null) return null
  if (latitude < -90 || latitude > 90) return null
  if (longitude < -180 || longitude > 180) return null
  if (accuracy < 0) return null

  return { latitude, longitude, altitude, accuracy }
}

function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return '0'
  if (Object.is(value, -0)) return '0'
  return String(value)
}

export function serializeGeoPoint(point: OdkGeoPoint): string {
  return [
    formatNumber(point.latitude),
    formatNumber(point.longitude),
    formatNumber(point.altitude),
    formatNumber(point.accuracy),
  ].join(' ')
}

export function pointFromGeolocation(position: GeolocationPosition): OdkGeoPoint {
  return {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    altitude: position.coords.altitude ?? 0,
    accuracy: position.coords.accuracy,
  }
}

export function parseGeoPath(value: string): OdkGeoPoint[] {
  if (!value.trim()) return []

  const chunks = value
    .split(/\s*;\s*|\r?\n+/)
    .map(part => part.trim())
    .filter(Boolean)

  const points: OdkGeoPoint[] = []
  for (const chunk of chunks) {
    const point = parseGeoPoint(chunk)
    if (!point) return []
    points.push(point)
  }
  return points
}

export function serializeGeoPath(points: OdkGeoPoint[]): string {
  return points.map(serializeGeoPoint).join(';')
}

export function sameGeoPoint(a: OdkGeoPoint, b: OdkGeoPoint): boolean {
  return a.latitude === b.latitude
    && a.longitude === b.longitude
    && a.altitude === b.altitude
    && a.accuracy === b.accuracy
}

export function isClosedGeoShape(points: OdkGeoPoint[]): boolean {
  return points.length >= 4 && sameGeoPoint(points[0], points[points.length - 1])
}

export function closeGeoShape(points: OdkGeoPoint[]): OdkGeoPoint[] {
  if (points.length < 3) {
    throw new Error('A geoshape needs at least three points before it can be closed.')
  }
  if (isClosedGeoShape(points)) return [...points]
  return [...points, { ...points[0] }]
}

export function geometryPreviewPoints(points: OdkGeoPoint[], width = 300, height = 160, padding = 12): string {
  if (points.length === 0) return ''

  const lats = points.map(point => point.latitude)
  const lons = points.map(point => point.longitude)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLon = Math.min(...lons)
  const maxLon = Math.max(...lons)
  const latRange = Math.max(maxLat - minLat, 0.000001)
  const lonRange = Math.max(maxLon - minLon, 0.000001)
  const usableWidth = Math.max(width - padding * 2, 1)
  const usableHeight = Math.max(height - padding * 2, 1)

  return points.map(point => {
    const x = padding + ((point.longitude - minLon) / lonRange) * usableWidth
    // SVG's Y axis grows downwards, so invert latitude.
    const y = padding + ((maxLat - point.latitude) / latRange) * usableHeight
    return `${x.toFixed(2)},${y.toFixed(2)}`
  }).join(' ')
}
