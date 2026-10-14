import type { CourseDto } from '../api/dto'
import { courseKey } from '../entities/courses'
import { IdList } from './IdList'
import { ItemCard } from './ItemCard'
import { ItemList } from './ItemList'
import { Sheet } from './Sheet'

type CourseCatalogProps = {
  courses: readonly CourseDto[]
  loading: boolean
  onRefresh: () => void
}

export function CourseCatalog({ courses, loading, onRefresh }: CourseCatalogProps) {
  return (
    <Sheet
      className="catalog"
      title={`◇ 개설 강좌 목록 (${courses.length})`}
      note="학점, 시간, 강의실, 수강인원은 수강신청 화면에서 확인할 수 있습니다."
      loading={loading}
      onRefresh={onRefresh}
    >
      <ItemList loading={loading} empty="개설 강좌가 없습니다.">
        {courses.map((course) => (
          <ItemCard
            key={courseKey(course)}
            title={`${course.courseId} ${course.courseName}`}
            lines={[
              `담당교수 : ${course.instructorName}`,
              <>
                선수과목 : <IdList ids={course.prerequisiteCourseIds} />
              </>,
            ]}
          />
        ))}
      </ItemList>
    </Sheet>
  )
}
