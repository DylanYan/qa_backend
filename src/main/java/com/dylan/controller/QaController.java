package com.dylan.controller;


import com.dylan.enums.QuestionCategory;
import com.dylan.service.NerService;
import com.dylan.service.QaService;
import com.dylan.util.JsonUtils;
import com.dylan.util.QuestionUtils;
import org.ansj.domain.Result;
import org.ansj.domain.Term;
import org.ansj.splitWord.analysis.NlpAnalysis;
import org.ansj.splitWord.analysis.ToAnalysis;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

@Controller
public class QaController {

    @Autowired
    private NerService nerService;

    @Autowired
    private QaService qaService;

    @RequestMapping(value = "/qa", method = RequestMethod.GET)
    @ResponseBody
    public String getAnswer(@RequestParam("question") String question) {
        long nerStartTime = System.currentTimeMillis();
        String subQuestion = null;
        List<String> entityList = new ArrayList<>();
        // 如果问句中包含实体的消岐释义，则在进行命名实体识别时要先剔除掉
        if (question.contains("[")) {
            int disAmbiStart = question.indexOf("[");
            int disAmbiEnd = question.indexOf("]") + 1;
            String disAmbi = question.substring(disAmbiStart, disAmbiEnd);
            subQuestion = StringUtils.remove(question, disAmbi);
            System.out.println("disAmbi: " + disAmbi + " subQuestion: " + subQuestion);
            entityList = nerService.getNameEntityList(subQuestion);
        } else {
            entityList = nerService.getNameEntityList(question);
        }
        long nerEndTime = System.currentTimeMillis();
        System.out.println("ner运行时间：" + (nerEndTime - nerStartTime) + "ms");
        if (Objects.isNull(entityList) || entityList.isEmpty()) {
            return JsonUtils.buildJsonStr(1, "抱歉！未能找到问句中的实体！");
        }
        System.out.println(entityList.toString());
//        if (entityList.size() > 2) {
//            return JsonUtils.buildJsonStr(1, "目前暂不支持多实体问答。");
//        }
        String entity = entityList.get(0);
        if (entityList.size() == 2) {
            for (String entityItem : entityList) {
                // 判断实体的位置是不是在“的”前面，如果问题中带有消岐释义，则使用去消岐释义的问题进行判断
                if (Objects.isNull(subQuestion)) {
                    int index = StringUtils.indexOf(question, entityItem) + entityItem.length();
                    if (index == StringUtils.indexOf(question, "的")) {
                        entity = entityItem;
                        break;
                    }
                } else {
                    int index = StringUtils.indexOf(subQuestion, entityItem) + entityItem.length();
                    if (index == StringUtils.indexOf(subQuestion, "的")) {
                        entity = entityItem;
                        break;
                    }
                }
            }
        }
        String questionCategory = QuestionUtils.getQuestionCategory(StringUtils.isBlank(subQuestion) ? question : subQuestion);
        if (questionCategory.equals(QuestionCategory.RAW.value())) {
            return JsonUtils.buildJsonStr(1, "抱歉！暂不支持此类型的问题。");
        }

        // 不包含“[”说明还未进行实体消歧
        if (!question.contains("[")) {
            List<String> ambiguousList = qaService.getAmbiguousList(entity);
            if (Objects.isNull(ambiguousList)) {
                return JsonUtils.buildJsonStr(1, "抱歉！知识库中不存在该实体。");
            }
            // 列表大小大于1说明需要消歧
            if (ambiguousList.size() > 1) {
                return JsonUtils.buildJsonStr(0, "知识库中包含多个名为“" + entity + "”的实体，请选择您需要查询的对象：\n" + ambiguousList.toString());
            }
        }

        // 找到消歧后的具体实体
        int fromIndex = question.indexOf("[");
        String ambiguousDescription = "";
        int toIndex = question.indexOf("]") + 1;
        // 不包含方括号说明知识库里只有一个该实体
        if (fromIndex != -1 && toIndex != 0) {
            ambiguousDescription = question.substring(fromIndex, toIndex);
        }
        String ambiguousEntity = entity + ambiguousDescription;
        System.out.println(ambiguousEntity);

        // 如果是简单实体类问题，直接返回实体描述
        if (questionCategory.equals(QuestionCategory.ENTITY.value())) {
            String desc = qaService.getEntityDesc(ambiguousEntity);
            if (StringUtils.isNotBlank(desc)) {
                return JsonUtils.buildJsonStr(0, desc);
            } else {
                return JsonUtils.buildJsonStr(1, "抱歉！知识库中不存在该实体。");
            }
        }

        Set<String> attributeSet = qaService.getEntityAttributeSet(ambiguousEntity);
        if (Objects.isNull(attributeSet) || attributeSet.isEmpty()) {
            return JsonUtils.buildJsonStr(1, "抱歉！知识库中不存在该实体的相关属性。");
        }
        System.out.println(ambiguousEntity + ": " + attributeSet.toString());
        // 对于每个歧义实体的属性，如果与问题中的属性匹配，则直接返回属性值
        long attrStartTime = System.currentTimeMillis();
        for (String attribute : attributeSet) {
            if (question.contains(attribute)) {
                List<String> valueList = qaService.getEntityAttributeValueList(ambiguousEntity, attribute);
                if (Objects.isNull(valueList) || valueList.isEmpty()) {
                    continue;
                }
                valueList = QuestionUtils.splitCombinedValue(valueList);
                StringBuilder valueListSb = new StringBuilder();
                valueListSb.append("[");
                Iterator iter = valueList.iterator();
                while (iter.hasNext()) {
                    valueListSb.append(iter.next());
                    if (iter.hasNext()) {
                        valueListSb.append(",");
                    }
                }
                valueListSb.append("]");
                String valueListStr = valueListSb.toString();
                if (questionCategory.equals(QuestionCategory.FACT.value())) {
//                    // 属性值太多了，就只显示前10个
//                    if (valueList.size() > 10) {
//                        valueList = valueList.subList(0, 9);
//                    }
                    long attrEndTime = System.currentTimeMillis();
                    System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                    return JsonUtils.buildJsonStr(0, ambiguousEntity + "的" + attribute + "是" + valueListStr + "。");
                }
                if (questionCategory.equals(QuestionCategory.YES_NO.value())) {
                    for (String value : valueList) {
                        if (question.contains(value)) {
                            if (valueList.size() == 1) {
                                long attrEndTime = System.currentTimeMillis();
                                System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                                return JsonUtils.buildJsonStr(0, "是的，" + ambiguousEntity + "的" + attribute + "是" + value + "。");
                            } else {
                                long attrEndTime = System.currentTimeMillis();
                                System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                                return JsonUtils.buildJsonStr(0, "是的，" + ambiguousEntity + "的" + attribute + "有" + valueListStr + "，包括" + value + "。");
                            }
                        }
                    }
                    if (valueList.size() == 1) {
                        long attrEndTime = System.currentTimeMillis();
                        System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                        return JsonUtils.buildJsonStr(0, "不是的，" + ambiguousEntity + "的" +attribute + "是" + valueList.get(0) + "。");
                    } else {
                        long attrEndTime = System.currentTimeMillis();
                        System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                        return JsonUtils.buildJsonStr(0, "不是的，" + ambiguousEntity + "的" +attribute + "有" + valueListStr + "。");
                    }
                }
                if (questionCategory.equals(QuestionCategory.QUANTITY.value())) {
                    int count = valueList.size();
                    String quantifier = "个";
                    Result segResult = NlpAnalysis.parse(question);
                    List<Term> termList = segResult.getTerms();
                    for (Term term : termList) {
                        if (term.getNatureStr().equals("m")) {
                            quantifier = StringUtils.substring(term.getName(), term.getName().length() - 1);
                            break;
                        } else if (term.getNatureStr().equals("q")) {
                            quantifier = term.getName();
                            break;
                        }
                    }
                    long attrEndTime = System.currentTimeMillis();
                    System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
                    return JsonUtils.buildJsonStr(0, ambiguousEntity + "有" + count + quantifier + attribute + "，是" + valueListStr +"。");
                }
            }
        }
        long attrEndTime = System.currentTimeMillis();
        System.out.println("属性匹配运行时间：" + (attrEndTime - attrStartTime) + "ms");
        return JsonUtils.buildJsonStr(1, "抱歉！目前知识库中不存在该问题的答案。");
    }

    @RequestMapping(value = "/seg", method = RequestMethod.GET)
    @ResponseBody
    public String getSeg(@RequestParam("question") String question) {
        Result segResult = NlpAnalysis.parse(question);
        List<Term> termList = segResult.getTerms();
        System.out.println(segResult.toString());
        for (Term term : termList) {
            System.out.println(term.getName());
            System.out.println(term.getRealName());
            System.out.println(term.getNatureStr());
        }
        String questionCategory = QuestionUtils.getQuestionCategory(question);
        return JsonUtils.buildJsonStr(0, questionCategory);
    }
}
