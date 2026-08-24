package jp.co.sss.lms.service;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import jp.co.sss.lms.dto.AttendanceManagementDto;
import jp.co.sss.lms.dto.LoginUserDto;
import jp.co.sss.lms.entity.TStudentAttendance;
import jp.co.sss.lms.enums.AttendanceStatusEnum;
import jp.co.sss.lms.form.AttendanceForm;
import jp.co.sss.lms.form.DailyAttendanceForm;
import jp.co.sss.lms.mapper.TStudentAttendanceMapper;
import jp.co.sss.lms.util.AttendanceUtil;
import jp.co.sss.lms.util.Constants;
import jp.co.sss.lms.util.DateUtil;
import jp.co.sss.lms.util.LoginUserUtil;
import jp.co.sss.lms.util.MessageUtil;
import jp.co.sss.lms.util.TrainingTime;

/**
 * 勤怠情報（受講生入力）サービス
 * 
 * @author 東京ITスクール
 */
@Service
public class StudentAttendanceService {

	@Autowired
	private DateUtil dateUtil;
	@Autowired
	private AttendanceUtil attendanceUtil;
	@Autowired
	private MessageUtil messageUtil;
	@Autowired
	private LoginUserUtil loginUserUtil;
	@Autowired
	private LoginUserDto loginUserDto;
	@Autowired
	private TStudentAttendanceMapper tStudentAttendanceMapper;

	/**
	 * 勤怠一覧情報取得
	 * 
	 * @param courseId
	 * @param lmsUserId
	 * @return 勤怠管理画面用DTOリスト
	 */
	public List<AttendanceManagementDto> getAttendanceManagement(Integer courseId,
			Integer lmsUserId) {

		// 勤怠管理リストの取得
		List<AttendanceManagementDto> attendanceManagementDtoList = tStudentAttendanceMapper
				.getAttendanceManagement(courseId, lmsUserId, Constants.DB_FLG_FALSE);
		for (AttendanceManagementDto dto : attendanceManagementDtoList) {
			// 中抜け時間を設定
			if (dto.getBlankTime() != null) {
				TrainingTime blankTime = attendanceUtil.calcBlankTime(dto.getBlankTime());
				dto.setBlankTimeValue(String.valueOf(blankTime));
			}
			// 遅刻早退区分判定
			AttendanceStatusEnum statusEnum = AttendanceStatusEnum.getEnum(dto.getStatus());
			if (statusEnum != null) {
				dto.setStatusDispName(statusEnum.name);
			}
		}

		return attendanceManagementDtoList;
	}

	public boolean notEnterCheck(Integer lmsUserId) throws ParseException {

		// 現在日付を取得 宮城真奈 - Task.25
		Date today = attendanceUtil.getTrainingDate();

		// 過去日の未入力件数を取得 宮城真奈 - Task.25
		int countResult = tStudentAttendanceMapper.notEnterCount(lmsUserId, (short) 0, today);
		System.out.println("未入力件数" + countResult);
		// 未入力が1件以上あればtrue 宮城真奈 - Task.25
		return countResult > 0;
	}

	/**
	 * 出退勤更新前のチェック
	 * 
	 * @param attendanceType
	 * @return エラーメッセージ
	 */
	public String punchCheck(Short attendanceType) {
		Date trainingDate = attendanceUtil.getTrainingDate();
		// 権限チェック
		if (!loginUserUtil.isStudent()) {
			return messageUtil.getMessage(Constants.VALID_KEY_AUTHORIZATION);
		}
		// 研修日チェック
		if (!attendanceUtil.isWorkDay(loginUserDto.getCourseId(), trainingDate)) {
			return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_NOTWORKDAY);
		}
		// 登録情報チェック
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
				.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(), trainingDate,
						Constants.DB_FLG_FALSE);
		switch (attendanceType) {
		case Constants.CODE_VAL_ATWORK:
			if (tStudentAttendance != null
					&& !tStudentAttendance.getTrainingStartTime().equals("")) {
				// 本日の勤怠情報は既に入力されています。直接編集してください。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHALREADYEXISTS);
			}
			break;
		case Constants.CODE_VAL_LEAVING:
			if (tStudentAttendance == null
					|| tStudentAttendance.getTrainingStartTime().equals("")) {
				// 出勤情報がないため退勤情報を入力出来ません。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHINEMPTY);
			}
			if (!tStudentAttendance.getTrainingEndTime().equals("")) {
				// 本日の勤怠情報は既に入力されています。直接編集してください。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHALREADYEXISTS);
			}
			TrainingTime trainingStartTime = new TrainingTime(
					tStudentAttendance.getTrainingStartTime());
			TrainingTime trainingEndTime = new TrainingTime();
			if (trainingStartTime.compareTo(trainingEndTime) > 0) {
				// 退勤時刻は出勤時刻より後でなければいけません。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_TRAININGTIMERANGE);
			}
			break;
		}
		return null;
	}

	/**
	 * 出勤ボタン処理
	 * 
	 * @return 完了メッセージ
	 */
	public String setPunchIn() {
		// 当日日付
		Date date = new Date();
		// 本日の研修日
		Date trainingDate = attendanceUtil.getTrainingDate();
		// 現在の研修時刻
		TrainingTime trainingStartTime = new TrainingTime();
		// 遅刻早退ステータス
		AttendanceStatusEnum attendanceStatusEnum = attendanceUtil.getStatus(trainingStartTime,
				null);
		// 研修日の勤怠情報取得
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
				.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(), trainingDate,
						Constants.DB_FLG_FALSE);
		if (tStudentAttendance == null) {
			// 登録処理
			tStudentAttendance = new TStudentAttendance();
			tStudentAttendance.setLmsUserId(loginUserDto.getLmsUserId());
			tStudentAttendance.setTrainingDate(trainingDate);
			tStudentAttendance.setTrainingStartTime(trainingStartTime.toString());
			tStudentAttendance.setTrainingEndTime("");
			tStudentAttendance.setStatus(attendanceStatusEnum.code);
			tStudentAttendance.setNote("");
			tStudentAttendance.setAccountId(loginUserDto.getAccountId());
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
			tStudentAttendance.setFirstCreateUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setFirstCreateDate(date);
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			tStudentAttendance.setBlankTime(null);
			tStudentAttendanceMapper.insert(tStudentAttendance);
		} else {
			// 更新処理
			tStudentAttendance.setTrainingStartTime(trainingStartTime.toString());
			tStudentAttendance.setStatus(attendanceStatusEnum.code);
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			tStudentAttendanceMapper.update(tStudentAttendance);
		}
		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}

	/**
	 * 退勤ボタン処理
	 * 
	 * @return 完了メッセージ
	 */
	public String setPunchOut() {
		// 当日日付
		Date date = new Date();
		// 本日の研修日
		Date trainingDate = attendanceUtil.getTrainingDate();
		// 研修日の勤怠情報取得
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
				.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(), trainingDate,
						Constants.DB_FLG_FALSE);
		// 出退勤時刻
		TrainingTime trainingStartTime = new TrainingTime(
				tStudentAttendance.getTrainingStartTime());
		TrainingTime trainingEndTime = new TrainingTime();
		// 遅刻早退ステータス
		AttendanceStatusEnum attendanceStatusEnum = attendanceUtil.getStatus(trainingStartTime,
				trainingEndTime);
		// 更新処理
		tStudentAttendance.setTrainingEndTime(trainingEndTime.toString());
		tStudentAttendance.setStatus(attendanceStatusEnum.code);
		tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
		tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
		tStudentAttendance.setLastModifiedDate(date);
		tStudentAttendanceMapper.update(tStudentAttendance);
		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}

	/**
	 * 勤怠フォームへ設定
	 * 
	 * @param attendanceManagementDtoList
	 * @return 勤怠編集フォーム
	 */
	public AttendanceForm setAttendanceForm(
			List<AttendanceManagementDto> attendanceManagementDtoList) {

		AttendanceForm attendanceForm = new AttendanceForm();
		attendanceForm.setAttendanceList(new ArrayList<DailyAttendanceForm>());
		attendanceForm.setLmsUserId(loginUserDto.getLmsUserId());
		attendanceForm.setUserName(loginUserDto.getUserName());
		attendanceForm.setLeaveFlg(loginUserDto.getLeaveFlg());
		attendanceForm.setBlankTimes(attendanceUtil.setBlankTime());
		//マップ取得 宮城真奈 - Task.26
		attendanceForm.setHourMap(attendanceUtil.setHourMap());
		attendanceForm.setMinuteMap(attendanceUtil.setMinuteMap());

		// 途中退校している場合のみ設定
		if (loginUserDto.getLeaveDate() != null) {
			attendanceForm
					.setLeaveDate(dateUtil.dateToString(loginUserDto.getLeaveDate(), "yyyy-MM-dd"));
			attendanceForm.setDispLeaveDate(
					dateUtil.dateToString(loginUserDto.getLeaveDate(), "yyyy年M月d日"));
		}

		// 勤怠管理リストの件数分、日次の勤怠フォームに移し替え
		for (AttendanceManagementDto attendanceManagementDto : attendanceManagementDtoList) {
			DailyAttendanceForm dailyAttendanceForm = new DailyAttendanceForm();
			dailyAttendanceForm
					.setStudentAttendanceId(attendanceManagementDto.getStudentAttendanceId());
			dailyAttendanceForm
					.setTrainingDate(dateUtil.toString(attendanceManagementDto.getTrainingDate()));
			dailyAttendanceForm
					.setTrainingStartTime(attendanceManagementDto.getTrainingStartTime());
			dailyAttendanceForm.setTrainingEndTime(attendanceManagementDto.getTrainingEndTime());

			//出勤時間を時・分に 宮城真奈 - Task.26
			String startTime = attendanceManagementDto.getTrainingStartTime();

			if (startTime != null && !startTime.isEmpty()) {
				Integer startHour = Integer.parseInt(startTime.substring(0, 2));
				Integer startMinute = Integer.parseInt(startTime.substring(3, 5));

				dailyAttendanceForm.setTrainingStartTimeHour(startHour);
				dailyAttendanceForm.setTrainingStartTimeMinute(startMinute);
			}

			//退勤時間を時・分に 宮城真奈 - Task.26
			String endTime = attendanceManagementDto.getTrainingEndTime();

			if (endTime != null && !endTime.isEmpty()) {
				Integer endHour = Integer.parseInt(endTime.substring(0, 2));
				Integer endMinute = Integer.parseInt(endTime.substring(3, 5));

				dailyAttendanceForm.setTrainingEndTimeHour(endHour);
				dailyAttendanceForm.setTrainingEndTimeMinute(endMinute);
			}

			if (attendanceManagementDto.getBlankTime() != null) {
				dailyAttendanceForm.setBlankTime(attendanceManagementDto.getBlankTime());
				dailyAttendanceForm.setBlankTimeValue(String.valueOf(
						attendanceUtil.calcBlankTime(attendanceManagementDto.getBlankTime())));
			}
			dailyAttendanceForm.setStatus(String.valueOf(attendanceManagementDto.getStatus()));
			dailyAttendanceForm.setNote(attendanceManagementDto.getNote());
			dailyAttendanceForm.setSectionName(attendanceManagementDto.getSectionName());
			dailyAttendanceForm.setIsToday(attendanceManagementDto.getIsToday());
			dailyAttendanceForm.setDispTrainingDate(dateUtil
					.dateToString(attendanceManagementDto.getTrainingDate(), "yyyy年M月d日(E)"));
			dailyAttendanceForm.setStatusDispName(attendanceManagementDto.getStatusDispName());

			attendanceForm.getAttendanceList().add(dailyAttendanceForm);
		}

		return attendanceForm;
	}

	/**
	 * 勤怠登録・更新処理
	 * 
	 * @param attendanceForm
	 * @return 完了メッセージ
	 * @throws ParseException
	 */
	public String update(AttendanceForm attendanceForm) throws ParseException {

		Integer lmsUserId = loginUserUtil.isStudent() ? loginUserDto.getLmsUserId()
				: attendanceForm.getLmsUserId();

		// 現在の勤怠情報（受講生入力）リストを取得
		List<TStudentAttendance> tStudentAttendanceList = tStudentAttendanceMapper
				.findByLmsUserId(lmsUserId, Constants.DB_FLG_FALSE);

		// 入力された情報を更新用のエンティティに移し替え
		Date date = new Date();
		for (DailyAttendanceForm dailyAttendanceForm : attendanceForm.getAttendanceList()) {

			// 更新用エンティティ作成
			TStudentAttendance tStudentAttendance = new TStudentAttendance();
			// 日次勤怠フォームから更新用のエンティティにコピー
			BeanUtils.copyProperties(dailyAttendanceForm, tStudentAttendance);
			// 研修日付
			tStudentAttendance
					.setTrainingDate(dateUtil.parse(dailyAttendanceForm.getTrainingDate()));
			// 現在の勤怠情報リストのうち、研修日が同じものを更新用エンティティで上書き
			for (TStudentAttendance entity : tStudentAttendanceList) {
				if (entity.getTrainingDate().equals(tStudentAttendance.getTrainingDate())) {
					tStudentAttendance = entity;
					break;
				}
			}
			tStudentAttendance.setLmsUserId(lmsUserId);
			tStudentAttendance.setAccountId(loginUserDto.getAccountId());
			// 出勤時刻整形
			TrainingTime trainingStartTime = null;
			trainingStartTime = new TrainingTime(dailyAttendanceForm.getTrainingStartTime());
			tStudentAttendance.setTrainingStartTime(trainingStartTime.getFormattedString());
			// 退勤時刻整形
			TrainingTime trainingEndTime = null;
			trainingEndTime = new TrainingTime(dailyAttendanceForm.getTrainingEndTime());
			tStudentAttendance.setTrainingEndTime(trainingEndTime.getFormattedString());
			// 中抜け時間
			tStudentAttendance.setBlankTime(dailyAttendanceForm.getBlankTime());
			// 遅刻早退ステータス
			if ((trainingStartTime != null || trainingEndTime != null)
					&& !dailyAttendanceForm.getStatusDispName().equals("欠席")) {
				AttendanceStatusEnum attendanceStatusEnum = attendanceUtil
						.getStatus(trainingStartTime, trainingEndTime);
				tStudentAttendance.setStatus(attendanceStatusEnum.code);
			}
			// 備考
			tStudentAttendance.setNote(dailyAttendanceForm.getNote());
			// 更新者と更新日時
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			// 削除フラグ
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
			// 登録用Listへ追加
			tStudentAttendanceList.add(tStudentAttendance);
		}
		// 登録・更新処理
		for (TStudentAttendance tStudentAttendance : tStudentAttendanceList) {
			if (tStudentAttendance.getStudentAttendanceId() == null) {
				tStudentAttendance.setFirstCreateUser(loginUserDto.getLmsUserId());
				tStudentAttendance.setFirstCreateDate(date);
				tStudentAttendanceMapper.insert(tStudentAttendance);
			} else {
				tStudentAttendanceMapper.update(tStudentAttendance);
			}
		}
		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}

	/**
	 * 勤怠入力チェック処理
	 * 
	 * @author 宮城真奈
	 * @param attendanceForm
	 * @param result
	 */
	public void updateInputCheck(AttendanceForm attendanceForm, BindingResult result) {


		for (DailyAttendanceForm dailyAttendanceForm : attendanceForm.getAttendanceList()) {

			Integer startHour = dailyAttendanceForm.getTrainingStartTimeHour();
			Integer startMinute = dailyAttendanceForm.getTrainingStartTimeMinute();

			Integer endHour = dailyAttendanceForm.getTrainingEndTimeHour();
			Integer endMinute = dailyAttendanceForm.getTrainingEndTimeMinute();
			
			//備考100文字超過チェック 宮城真奈 - Task27
			if(dailyAttendanceForm.getNote() != null && dailyAttendanceForm.getNote().length() > 100) {
				
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].note", 
						messageUtil.getMessage(
								"maxlength", new String[] {"備考", "100"})));
			}

			//出勤時間の時のエラーチェック 宮城真奈 - Task27
			if (startHour == null && startMinute != null){
				
				//時にエラー
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingStartTimeHour", 
						messageUtil.getMessage(
								"input.invalid", new String[] {"出勤時間"})));
				
			}
			
			//出勤時間の分のエラーチェック 宮城真奈 - Task27
			if (startHour != null && startMinute == null) {
				
				//分にエラー
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingStartTimeMinute", 
						messageUtil.getMessage(
								"input.invalid", new String[] {"出勤時間"})));
			}
			
			//退勤時間の時のエラーチェック 宮城真奈 - Task27
			if (endHour == null && endMinute != null) {

				//時にエラー
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingEndTimeHour", 
						messageUtil.getMessage(
								"input.invalid", new String[] {"退勤時間"})));
				
			}
			
			//退勤時間の分のエラーチェック 宮城真奈 - Task27
			if (endHour != null && endMinute == null) {
				
				//分にエラー
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingEndTimeMinute", 
						messageUtil.getMessage(
								"input.invalid", new String[] {"退勤時間"})));
			}
			
			//出勤時間なしで退勤時間あり 宮城真奈 - Task27
			if(startHour == null && startMinute == null && endHour != null && endMinute != null) {
				
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingStartTimeHour", 
						messageUtil.getMessage("attendance.punchInEmpty")));
			}
			
			//出勤時間 > 退勤時間 宮城真奈 - Task27
			if(startHour != null && startMinute != null && endHour != null && endMinute != null) {
							
			TrainingTime startTime = new TrainingTime(startHour, startMinute);
			TrainingTime endTime = new TrainingTime(endHour, endMinute);
			
			
			if(startTime.compareTo(endTime) > 0) {
				
				result.addError(new FieldError(
						result.getObjectName(), 
						"attendanceList[" + 
						attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
						"].trainingStartTimeHour", 
						messageUtil.getMessage(
								"attendance.trainingTimeRange", 
								new String[] {String.valueOf(
										attendanceForm.getAttendanceList().indexOf(
												dailyAttendanceForm) + 1)})));
				
			}
							
						}
			
			//中抜け時間チェック 宮城真奈 - Task27
			if(dailyAttendanceForm.getBlankTime() != null 
					&& startHour != null 
					&& startMinute != null 
					&& endHour != null 
					&& endMinute != null) {
				
				//分に変換
				int workTime = (endHour * 60 + endMinute) - (startHour * 60 + startMinute);
				
				if(dailyAttendanceForm.getBlankTime() > workTime) {
					
					result.addError(new FieldError(
							result.getObjectName(), 
							"attendanceList[" + 
							attendanceForm.getAttendanceList().indexOf(dailyAttendanceForm) + 
							"].blankTime", 
							messageUtil.getMessage("attendance.blankTimeError")));
					
				}
			}
			
			}
		}
	
	/**
	 * 出勤/退勤時間をHH:mm形式に変換
	 * 
	 * @author 宮城真奈
	 * @param attendanceForm
	 */
	public void formatConversion(AttendanceForm attendanceForm) {
		
		for (DailyAttendanceForm dailyAttendanceForm : attendanceForm.getAttendanceList()) {

			//出勤時間を HH:mm 形式に 宮城真奈 - Task.26
			if (dailyAttendanceForm.getTrainingStartTimeHour() != null
					&& dailyAttendanceForm.getTrainingStartTimeMinute() != null) {
				dailyAttendanceForm.setTrainingStartTime(String.format("%02d:%02d",
						dailyAttendanceForm.getTrainingStartTimeHour(),
						dailyAttendanceForm.getTrainingStartTimeMinute()));
			}

			//退勤時間を HH:mm 形式に 宮城真奈 - Task.26
			if (dailyAttendanceForm.getTrainingEndTimeHour() != null
					&& dailyAttendanceForm.getTrainingEndTimeMinute() != null) {
				dailyAttendanceForm.setTrainingEndTime(String.format("%02d:%02d",
						dailyAttendanceForm.getTrainingEndTimeHour(),
						dailyAttendanceForm.getTrainingEndTimeMinute()));
			}
	}
	}

}
