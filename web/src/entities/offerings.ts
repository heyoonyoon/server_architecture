import type { OfferingDto } from '../api/dto'

export const offeringKey = (offering: OfferingDto) => String(offering.offeringId)

export const enrolledCount = (offering: OfferingDto) => Math.max(0, offering.capacity - offering.remaining)

export const isFull = (offering: OfferingDto) => offering.remaining <= 0
