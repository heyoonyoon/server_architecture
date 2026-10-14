import type { MyEnrollmentsDto } from '../api/dto'

type EnrollmentSummaryProps = { mine: MyEnrollmentsDto }

export function EnrollmentSummary({ mine }: EnrollmentSummaryProps) {
  return (
    <div className="summary">
      <table className="summary-table">
        <thead>
          <tr>
            <th colSpan={3} className="summary-title">
              수강신청 현황
            </th>
          </tr>
          <tr>
            <th>수강가능학점</th>
            <th>총 수강신청 강좌 수</th>
            <th>총 수강신청 학점 수</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{mine.maxCredits}</td>
            <td>{mine.offerings.length}</td>
            <td>{mine.totalCredits}</td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}
