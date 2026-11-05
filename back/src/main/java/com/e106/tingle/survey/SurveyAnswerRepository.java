package com.e106.tingle.survey;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, SurveyAnswer.Key> {

	List<SurveyAnswer> findBySurveyId(Long surveyId);
}
