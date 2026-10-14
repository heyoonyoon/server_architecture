import {
  integer,
  millis,
  stringList,
  text,
  type EnrollRequest,
  type MessageResponse,
  type MyEnrollmentsDto,
  type MyEnrollmentsResponse,
  type OfferingDto,
  type OfferingListResponse,
  type OfferingResponse,
  type OfferingTimeDto,
  type QueueState,
  type QueueStatusDto,
  type QueueStatusResponse,
  type ScheduleDto,
  type ScheduleResponse,
} from './dto'
import { http } from './http'

export type ActionResult = { success: boolean; message: string }

const queueStates: readonly QueueState[] = ['NOT_IN_QUEUE', 'WAITING', 'ADMITTED']

function toQueueState(state: unknown): QueueState {
  return queueStates.find((candidate) => candidate === state) ?? 'NOT_IN_QUEUE'
}

function toQueueStatus(status: QueueStatusResponse): QueueStatusDto {
  return {
    state: toQueueState(status.state),
    position: integer(status.position),
    nextPollMs: integer(status.nextPollMs),
    passRemainingSeconds: integer(status.passRemainingSeconds),
  }
}

function toOfferingTime(time: Partial<OfferingTimeDto>): OfferingTimeDto {
  return { dayOfWeek: text(time.dayOfWeek), startTime: text(time.startTime), endTime: text(time.endTime) }
}

function toOffering(offering: OfferingResponse): OfferingDto {
  return {
    offeringId: integer(offering.offeringId),
    courseId: text(offering.courseId),
    courseName: text(offering.courseName),
    credits: integer(offering.credits),
    semester: text(offering.semester),
    section: text(offering.section),
    professorId: text(offering.professorId),
    professorName: text(offering.professorName),
    room: text(offering.room),
    capacity: integer(offering.capacity),
    remaining: integer(offering.remaining),
    times: (offering.times ?? []).map(toOfferingTime),
    prerequisiteCourseIds: stringList(offering.prerequisiteCourseIds),
  }
}

function toOfferings(response: OfferingListResponse): OfferingDto[] {
  return (response.offerings ?? []).map(toOffering)
}

function toActionResult(response: Partial<MessageResponse>): ActionResult {
  return { success: response.success === true, message: text(response.message) }
}

export const queueApi = {
  enter: async () => toQueueStatus(await http.post<QueueStatusResponse>('/queue', {})),
  status: async () => toQueueStatus(await http.get<QueueStatusResponse>('/queue')),
  leave: async (keepalive = false) => {
    await http.delete<unknown>('/queue', { keepalive })
  },
}

export const scheduleApi = {
  get: async (): Promise<ScheduleDto> => {
    const response = await http.get<ScheduleResponse>('/schedule')
    return { openAtMs: millis(response.openAtMs), serverNowMs: millis(response.serverNowMs) }
  },
}

export const enrollmentApi = {
  listOfferings: async () => toOfferings(await http.get<OfferingListResponse>('/offerings')),
  listMine: async (): Promise<MyEnrollmentsDto> => {
    const response = await http.get<MyEnrollmentsResponse>('/me/enrollments')
    return {
      offerings: toOfferings(response),
      totalCredits: integer(response.totalCredits),
      maxCredits: integer(response.maxCredits),
    }
  },
  enroll: async (offeringId: number) => {
    const request: EnrollRequest = { offeringId }
    return toActionResult(await http.post<Partial<MessageResponse>>('/enrollments', request))
  },
  cancel: async (offeringId: number) =>
    toActionResult(await http.delete<Partial<MessageResponse>>(`/enrollments/${offeringId}`)),
}
