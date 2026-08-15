import { describe, expect, it } from 'vitest'
import {
  closeGeoShape,
  geometryPreviewPoints,
  isClosedGeoShape,
  parseGeoPath,
  parseGeoPoint,
  serializeGeoPath,
  serializeGeoPoint,
} from './geo'

describe('ODK geo value helpers', () => {
  it('parses and serializes a canonical geopoint', () => {
    const point = parseGeoPoint('0.3476 32.5825 120 4.5')
    expect(point).toEqual({
      latitude: 0.3476,
      longitude: 32.5825,
      altitude: 120,
      accuracy: 4.5,
    })
    expect(serializeGeoPoint(point!)).toBe('0.3476 32.5825 120 4.5')
  })

  it('rejects coordinates outside latitude/longitude bounds', () => {
    expect(parseGeoPoint('91 32 0 5')).toBeNull()
    expect(parseGeoPoint('0 181 0 5')).toBeNull()
    expect(parseGeoPoint('0 32 0 -1')).toBeNull()
  })

  it('accepts semicolon or newline paths and serializes them with ODK semicolons', () => {
    const points = parseGeoPath('0 32 0 5\n0.1 32.1 0 4')
    expect(points).toHaveLength(2)
    expect(serializeGeoPath(points)).toBe('0 32 0 5;0.1 32.1 0 4')
  })

  it('closes geoshapes by repeating the first point', () => {
    const open = parseGeoPath('0 32 0 5;0.1 32.1 0 4;0.2 32 0 3')
    const closed = closeGeoShape(open)
    expect(closed).toHaveLength(4)
    expect(isClosedGeoShape(closed)).toBe(true)
    expect(serializeGeoPath(closed)).toBe('0 32 0 5;0.1 32.1 0 4;0.2 32 0 3;0 32 0 5')
  })

  it('requires three points before closing a geoshape', () => {
    expect(() => closeGeoShape(parseGeoPath('0 32 0 5;0.1 32.1 0 4')))
      .toThrow('at least three points')
  })

  it('creates a bounded SVG preview polyline', () => {
    const preview = geometryPreviewPoints(parseGeoPath('0 32 0 5;1 33 0 5'))
    expect(preview).toMatch(/^\d+\.\d{2},\d+\.\d{2} \d+\.\d{2},\d+\.\d{2}$/)
  })
})
