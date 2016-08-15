package teammates.ui.controller;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import teammates.common.datatransfer.FeedbackParticipantType;
import teammates.common.datatransfer.FeedbackPathAttributes;
import teammates.common.datatransfer.FeedbackQuestionAttributes;
import teammates.common.datatransfer.FeedbackQuestionDetails;
import teammates.common.datatransfer.FeedbackQuestionType;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.datatransfer.StudentAttributes;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.TeammatesException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.Const.StatusMessageColor;
import teammates.common.util.HttpRequestHelper;
import teammates.common.util.StatusMessage;
import teammates.common.util.StringHelper;
import teammates.logic.api.GateKeeper;

import com.google.appengine.api.datastore.Text;

public class InstructorFeedbackQuestionEditAction extends Action {

    @Override
    protected ActionResult execute() throws EntityDoesNotExistException {
        String courseId = getRequestParamValue(Const.ParamsNames.COURSE_ID);
        String feedbackSessionName = getRequestParamValue(Const.ParamsNames.FEEDBACK_SESSION_NAME);
        
        Assumption.assertPostParamNotNull(Const.ParamsNames.COURSE_ID, courseId);
        Assumption.assertPostParamNotNull(Const.ParamsNames.FEEDBACK_SESSION_NAME, feedbackSessionName);
        
        new GateKeeper().verifyAccessible(logic.getInstructorForGoogleId(courseId, account.googleId),
                                          logic.getFeedbackSession(feedbackSessionName, courseId),
                                          false, Const.ParamsNames.INSTRUCTOR_PERMISSION_MODIFY_SESSION);

        String editType = getRequestParamValue(Const.ParamsNames.FEEDBACK_QUESTION_EDITTYPE);
        Assumption.assertNotNull("Null editType", editType);
        
        FeedbackQuestionAttributes updatedQuestion = extractFeedbackQuestionData(requestParameters);
        
        try {
            if ("edit".equals(editType)) {
                String questionText = HttpRequestHelper.getValueFromParamMap(
                                        requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_TEXT);
                Assumption.assertNotNull("Null question text", questionText);
                Assumption.assertNotEmpty("Empty question text", questionText);
                
                editQuestion(updatedQuestion);
            } else if ("delete".equals(editType)) {
                // branch not tested because if it's not edit or delete, Assumption.fail will cause test failure
                deleteQuestion(updatedQuestion);
            } else {
                // Assumption.fails are not tested
                Assumption.fail("Invalid editType");
            }
        } catch (InvalidParametersException e) {
            // This part is not tested because GateKeeper handles if this happens, would be
            // extremely difficult to replicate a situation whereby it gets past GateKeeper
            setStatusForException(e);
        }
        
        return createRedirectResult(new PageData(account)
                                            .getInstructorFeedbackEditLink(courseId, feedbackSessionName));
    }

    private void deleteQuestion(FeedbackQuestionAttributes updatedQuestion) {
        logic.deleteFeedbackQuestion(updatedQuestion.getId());
        statusToUser.add(new StatusMessage(Const.StatusMessages.FEEDBACK_QUESTION_DELETED, StatusMessageColor.SUCCESS));
        statusToAdmin = "Feedback Question " + updatedQuestion.questionNumber + " for session:<span class=\"bold\">("
                        + updatedQuestion.feedbackSessionName + ")</span> for Course <span class=\"bold\">["
                        + updatedQuestion.courseId + "]</span> deleted.<br>";
    }

    private void editQuestion(FeedbackQuestionAttributes updatedQuestion) throws InvalidParametersException,
                                                                                 EntityDoesNotExistException {
        String err = validateQuestionGiverRecipientVisibility(updatedQuestion);
        
        if (!err.isEmpty()) {
            statusToUser.add(new StatusMessage(err, StatusMessageColor.DANGER));
            isError = true;
        }
   
        FeedbackQuestionDetails updatedQuestionDetails = updatedQuestion.getQuestionDetails();
        List<String> questionDetailsErrors = updatedQuestionDetails.validateQuestionDetails();
        List<StatusMessage> questionDetailsErrorsMessages = new ArrayList<StatusMessage>();

        for (String error : questionDetailsErrors) {
            questionDetailsErrorsMessages.add(new StatusMessage(error, StatusMessageColor.DANGER));
        }
        
        List<StudentAttributes> studentsInCourse = logic.getStudentsForCourse(updatedQuestion.getCourseId());
        List<InstructorAttributes> instructorsInCourse = logic.getInstructorsForCourse(updatedQuestion.getCourseId());
        String feedbackPathsParticipantsError =
                validateQuestionFeedbackPathsParticipants(
                        updatedQuestion, studentsInCourse, instructorsInCourse);
        StatusMessage feedbackPathsParticipantsErrorMessage =
                new StatusMessage(feedbackPathsParticipantsError, StatusMessageColor.DANGER);

        if (questionDetailsErrors.isEmpty() && feedbackPathsParticipantsError.isEmpty()) {
            logic.updateFeedbackQuestionNumber(updatedQuestion);
            
            statusToUser.add(new StatusMessage(Const.StatusMessages.FEEDBACK_QUESTION_EDITED, StatusMessageColor.SUCCESS));
            statusToAdmin = "Feedback Question " + updatedQuestion.questionNumber
                          + " for session:<span class=\"bold\">("
                          + updatedQuestion.feedbackSessionName + ")</span> for Course <span class=\"bold\">["
                          + updatedQuestion.courseId + "]</span> edited.<br>"
                          + "<span class=\"bold\">"
                          + updatedQuestionDetails.getQuestionTypeDisplayName() + ":</span> "
                          + updatedQuestionDetails.getQuestionText();
        } else {
            statusToUser.addAll(questionDetailsErrorsMessages);
            statusToUser.add(feedbackPathsParticipantsErrorMessage);
            isError = true;
        }
    }
    
    /**
     * Validates that the giver and recipient for the given FeedbackQuestionAttributes is valid for its question type.
     * Validates that the visibility for the given FeedbackQuestionAttributes is valid for its question type.
     * 
     * @param feedbackQuestionAttributes
     * @return error message detailing the error, or an empty string if valid.
     */
    public static String validateQuestionGiverRecipientVisibility(FeedbackQuestionAttributes feedbackQuestionAttributes) {
        String errorMsg = "";
        
        FeedbackQuestionDetails questionDetails = null;
        Class<? extends FeedbackQuestionDetails> questionDetailsClass = feedbackQuestionAttributes
                                                                            .questionType.getQuestionDetailsClass();
        Constructor<? extends FeedbackQuestionDetails> questionDetailsClassConstructor;
        
        try {
            questionDetailsClassConstructor = questionDetailsClass.getConstructor();
            questionDetails = questionDetailsClassConstructor.newInstance();
            Method m = questionDetailsClass.getMethod("validateGiverRecipientVisibility",
                                                      FeedbackQuestionAttributes.class);
            errorMsg = (String) m.invoke(questionDetails, feedbackQuestionAttributes);
            
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
                 | InvocationTargetException | InstantiationException e) {
            log.severe(TeammatesException.toStringWithStackTrace(e));
            // Assumption.fails are not tested
            Assumption.fail("Failed to instantiate Feedback*QuestionDetails instance for "
                            + feedbackQuestionAttributes.questionType.toString() + " question type.");
        }
        
        return errorMsg;
    }
    
    public static String validateQuestionFeedbackPathsParticipants(
            FeedbackQuestionAttributes question, List<StudentAttributes> students,
            List<InstructorAttributes> instructors) {
        String errorMsg = "";
        
        Set<String> studentEmails = new HashSet<String>();
        Set<String> instructorEmails = new HashSet<String>();
        Set<String> teamNames = new HashSet<String>();
        
        for (StudentAttributes student : students) {
            studentEmails.add(student.getEmail());
            teamNames.add(student.getTeam());
        }
        
        for (InstructorAttributes instructor : instructors) {
            instructorEmails.add(instructor.getEmail());
        }
        
        Set<String> nonExistentParticipants = new HashSet<String>();
        
        for (FeedbackPathAttributes feedbackPath : question.feedbackPaths) {
            boolean isFeedbackPathGiverTypeNonExistent =
                    feedbackPath.getFeedbackPathGiverType().isEmpty();
            boolean isFeedbackPathGiverStudentNonExistent =
                    feedbackPath.isFeedbackPathGiverAStudent()
                    && !studentEmails.contains(feedbackPath.getGiverId());
            boolean isFeedbackPathGiverInstructorNonExistent =
                    feedbackPath.isFeedbackPathGiverAnInstructor()
                    && !instructorEmails.contains(feedbackPath.getGiverId());
            boolean isFeedbackPathGiverTeamNonExistent =
                    feedbackPath.isFeedbackPathGiverATeam()
                    && !teamNames.contains(feedbackPath.getGiverId());
            
            boolean isFeedbackPathRecipientTypeNonExistent =
                    feedbackPath.getFeedbackPathRecipientType().isEmpty();
            boolean isFeedbackPathRecipientStudentNonExistent =
                    feedbackPath.isFeedbackPathRecipientAStudent()
                    && !studentEmails.contains(feedbackPath.getRecipientId());
            boolean isFeedbackPathRecipientInstructorNonExistent =
                    feedbackPath.isFeedbackPathRecipientAnInstructor()
                    && !instructorEmails.contains(feedbackPath.getRecipientId());
            boolean isFeedbackPathRecipientTeamNonExistent =
                    feedbackPath.isFeedbackPathRecipientATeam()
                    && !teamNames.contains(feedbackPath.getRecipientId());
            
            if (isFeedbackPathGiverTypeNonExistent
                    || isFeedbackPathGiverStudentNonExistent
                    || isFeedbackPathGiverInstructorNonExistent
                    || isFeedbackPathGiverTeamNonExistent) {
                nonExistentParticipants.add(feedbackPath.getGiver());
            }
            
            if (isFeedbackPathRecipientTypeNonExistent
                    || isFeedbackPathRecipientStudentNonExistent
                    || isFeedbackPathRecipientInstructorNonExistent
                    || isFeedbackPathRecipientTeamNonExistent) {
                nonExistentParticipants.add(feedbackPath.getRecipient());
            }
        }
        
        if (!nonExistentParticipants.isEmpty()) {
            errorMsg = "Unable to save question as the following feedback path participants do not exist: "
                       + StringHelper.removeEnclosingSquareBrackets(nonExistentParticipants.toString());
        }
        
        return errorMsg;
    }

    private static FeedbackQuestionAttributes extractFeedbackQuestionData(Map<String, String[]> requestParameters) {
        FeedbackQuestionAttributes newQuestion = new FeedbackQuestionAttributes();
        
        newQuestion.setId(HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                                 Const.ParamsNames.FEEDBACK_QUESTION_ID));
        Assumption.assertNotNull("Null question id", newQuestion.getId());
        
        newQuestion.courseId = HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                                      Const.ParamsNames.COURSE_ID);
        Assumption.assertNotNull("Null course id", newQuestion.courseId);
        
        newQuestion.feedbackSessionName =
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_SESSION_NAME);
        Assumption.assertNotNull("Null feedback session name", newQuestion.feedbackSessionName);
        
        // TODO thoroughly investigate when and why these parameters can be null
        // and check all possibilities in the tests
        // should only be null when deleting. might be good to separate the delete action from this class
        
        // When editing, usually the following fields are not null. If they are null somehow(edit from browser),
        // Then the field will not update and take on its old value.
        // When deleting, the following fields are null.
        // numofrecipients
        // questiontext
        // numofrecipientstype
        // recipienttype
        // receiverLeaderCheckbox
        // givertype
        
        // Can be null
        String giverType = HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                                  Const.ParamsNames.FEEDBACK_QUESTION_GIVERTYPE);
        if (giverType != null) {
            newQuestion.giverType = FeedbackParticipantType.valueOf(giverType);
        }
        
        // Can be null
        String recipientType =
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_RECIPIENTTYPE);
        if (recipientType != null) {
            newQuestion.recipientType = FeedbackParticipantType.valueOf(recipientType);
        }

        String questionNumber =
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_NUMBER);
        Assumption.assertNotNull("Null question number", questionNumber);
        newQuestion.questionNumber = Integer.parseInt(questionNumber);
        Assumption.assertTrue("Invalid question number", newQuestion.questionNumber >= 1);
        
        // Can be null
        String nEntityTypes =
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_NUMBEROFENTITIESTYPE);
        
        if (numberOfEntitiesIsUserDefined(newQuestion.recipientType, nEntityTypes)) {
            String nEntities;
            nEntities = HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                               Const.ParamsNames.FEEDBACK_QUESTION_NUMBEROFENTITIES);
            Assumption.assertNotNull(nEntities);
            newQuestion.numberOfEntitiesToGiveFeedbackTo = Integer.parseInt(nEntities);
        } else {
            newQuestion.numberOfEntitiesToGiveFeedbackTo = Const.MAX_POSSIBLE_RECIPIENTS;
        }
        
        if (newQuestion.giverType == FeedbackParticipantType.CUSTOM
                && newQuestion.recipientType == FeedbackParticipantType.CUSTOM) {
            String customFeedbackPathsSpreadsheetData =
                    HttpRequestHelper.getValueFromParamMap(
                            requestParameters, "custom-feedback-paths-spreadsheet-data");
            
            newQuestion.feedbackPaths =
                    FeedbackQuestionAttributes.getFeedbackPathsFromSpreadsheetData(
                            newQuestion.courseId, customFeedbackPathsSpreadsheetData);
        }
        
        newQuestion.showResponsesTo = getParticipantListFromParams(
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_SHOWRESPONSESTO));
        newQuestion.showGiverNameTo = getParticipantListFromParams(
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_SHOWGIVERTO));
        newQuestion.showRecipientNameTo = getParticipantListFromParams(
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_SHOWRECIPIENTTO));
        
        String questionType = HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                                     Const.ParamsNames.FEEDBACK_QUESTION_TYPE);
        Assumption.assertNotNull(questionType);
        newQuestion.questionType = FeedbackQuestionType.valueOf(questionType);
        
        // Can be null
        String questionText = HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                                     Const.ParamsNames.FEEDBACK_QUESTION_TEXT);
        if (questionText != null && !questionText.isEmpty()) {
            FeedbackQuestionDetails questionDetails = FeedbackQuestionDetails.createQuestionDetails(
                    requestParameters, newQuestion.questionType);
            newQuestion.setQuestionDetails(questionDetails);
        }

        String questionDescription = HttpRequestHelper.getValueFromParamMap(requestParameters,
                Const.ParamsNames.FEEDBACK_QUESTION_DESCRIPTION);

        newQuestion.setQuestionDescription(new Text(questionDescription));

        return newQuestion;
    }
    
    private static boolean numberOfEntitiesIsUserDefined(FeedbackParticipantType recipientType, String nEntityTypes) {
        if (recipientType != FeedbackParticipantType.STUDENTS
                && recipientType != FeedbackParticipantType.TEAMS) {
            return false;
        }
        
        return "custom".equals(nEntityTypes);
    }

    private static List<FeedbackParticipantType> getParticipantListFromParams(String params) {
        List<FeedbackParticipantType> list = new ArrayList<FeedbackParticipantType>();
        
        if (params.isEmpty()) {
            return list;
        }
        
        String[] splitString = params.split(",");
        
        for (String str : splitString) {
            list.add(FeedbackParticipantType.valueOf(str));
        }
        
        return list;
    }
}
