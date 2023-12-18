package org.sunbird.v5.managers

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.sunbird.common.{DateUtils, JsonUtils, Platform}
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.{ClientException, ServerException}
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.graph.nodes.DataNode
import org.sunbird.graph.schema.{DefinitionNode, ObjectCategoryDefinition}
import org.sunbird.graph.utils.NodeUtil
import org.sunbird.managers.HierarchyManager
import org.sunbird.telemetry.util.LogTelemetryEventUtil
import org.sunbird.utils.{AssessmentConstants, AssessmentErrorCodes, RequestUtil}

import java.util
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import com.mashape.unirest.http.Unirest
import org.apache.http.HttpResponse
import org.sunbird.utils.{AssessmentConstants, JavaJsonUtils, RequestUtil}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.sunbird.common.exception.{ClientException, ErrorCodes, ResourceNotFoundException, ServerException}

object AssessmentV5Manager {

  val defaultVersion = Platform.config.getNumber("v5_default_qumlVersion")
  val supportedVersions: java.util.List[Number] = Platform.config.getNumberList("v5_supported_qumlVersions")
  val skipValidation: Boolean = Platform.getBoolean("assessment.skip.validation", false)
  val validStatus = List("Draft", "Review")
  val mapper = new ObjectMapper()
  val map = Map("userId" -> "userID", "attemptId" -> "attemptID")

  def validateAndGetVersion(ver: AnyRef): AnyRef = {
    if (supportedVersions.contains(ver)) ver else throw new ClientException(AssessmentErrorCodes.ERR_REQUEST_DATA_VALIDATION, s"Platform doesn't support quml version ${ver} | Currently Supported quml version are: ${supportedVersions}")
  }

  def validateVisibilityForCreate(request: Request, errCode: String): Unit = {
    val visibility: String = request.getRequest.getOrDefault("visibility", "").asInstanceOf[String]
    if (StringUtils.isNotBlank(visibility) && StringUtils.equalsIgnoreCase(visibility, "Parent"))
      throw new ClientException(errCode, "Visibility Cannot Be Parent!")
  }

  def create(request: Request)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
    validateVisibilityForCreate(request, AssessmentErrorCodes.ERR_REQUEST_DATA_VALIDATION)
    val qumlVersion = request.getRequest.getOrDefault("qumlVersion", 0.0.asInstanceOf[AnyRef])
    val version = if (qumlVersion != 0.0) validateAndGetVersion(qumlVersion) else defaultVersion
    request.put("qumlVersion", version)
    request.getContext.put("version", version.toString)
    RequestUtil.restrictProperties(request)
    request.put("schemaVersion", version.toString)
    DataNode.create(request).map(node => {
      val response = ResponseHandler.OK
      response.putAll(Map("identifier" -> node.getIdentifier, "versionKey" -> node.getMetadata.get("versionKey")).asJava)
      response
    })
  }

  def convertOneOfProps(node: Node, metadata: util.Map[String, AnyRef])(implicit oec: OntologyEngineContext, ec: ExecutionContext): util.Map[String, AnyRef] = {
    val objectCategoryDefinition: ObjectCategoryDefinition = DefinitionNode.getObjectCategoryDefinition(node.getMetadata.getOrDefault("primaryCategory", "").asInstanceOf[String], node.getObjectType.toLowerCase().replace("image", ""), node.getMetadata.getOrDefault("channel", "all").asInstanceOf[String])
    val oneOfProps = DefinitionNode.fetchOneOfProps(node.getGraphId, node.getMetadata.getOrDefault("schemaVersion", 1.0.asInstanceOf[AnyRef]).toString, node.getObjectType.toLowerCase().replace("image", ""), objectCategoryDefinition)
    oneOfProps.foreach(key => {
      if (metadata.containsKey(key)) {
        val data: AnyRef = try {
          JsonUtils.deserialize(metadata.getOrDefault(key, "").asInstanceOf[String], classOf[java.util.Map[String, AnyRef]])
        } catch {
          case _ => metadata.getOrDefault(key, "")
        }
        metadata.put(key, data)
      }
    })
    metadata
  }

  def getQuestionMetadata(node: Node, fields: util.List[String], extFields: util.List[String])(implicit oec: OntologyEngineContext, ec: ExecutionContext): util.Map[String, AnyRef] = {
    val version: AnyRef = node.getMetadata.getOrDefault("schemaVersion", 1.0.asInstanceOf[AnyRef])
    val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, List().asJava, node.getObjectType.toLowerCase.replace("Image", ""), version.toString)
    val updatedMeta = if (version.toString == "1.0") getTransformedQuestionMetadata(metadata) else convertOneOfProps(node, metadata)
    if (CollectionUtils.isNotEmpty(fields))
      updatedMeta.keySet.retainAll(fields)
    else updatedMeta.keySet().removeAll(extFields)
    updatedMeta.put("identifier", node.getIdentifier.replace(".img", ""))
    updatedMeta
  }

  def getValidateNodeForReject(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
    request.put("mode", "edit")
    DataNode.read(request).map(node => {
      val serverEvaluable = node.getMetadata.get(AssessmentConstants.EVAL)
      val data = serverEvaluable
      if (data  != null && data == AssessmentConstants.SERVER && !StringUtils.equals(request.getOrDefault("isEditor", "").asInstanceOf[String], "true")) {
        val hideEditorResponse = hideEditorStateAns(node)
        if (StringUtils.isNotEmpty(hideEditorResponse))
          node.getMetadata.put(AssessmentConstants.EDITOR_STATE, hideEditorResponse)
        val hideCorrectAns = hideCorrectResponse(node)
        if (StringUtils.isNotEmpty(hideCorrectAns))
          node.getMetadata.put(AssessmentConstants.RESPONSE_DECLARATION, hideCorrectAns)
      }
      if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
        throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with visibility Parent, can't be sent for reject individually.")
      if (!StringUtils.equalsIgnoreCase("Review", node.getMetadata.get("status").asInstanceOf[String]))
        throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} is not in 'Review' state for identifier: " + node.getIdentifier)
      node
    })
  }

  def getValidatedNodeForRetire(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
    DataNode.read(request).map(node => {
      if (StringUtils.equalsIgnoreCase("Retired", node.getMetadata.get("status").asInstanceOf[String]))
        throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with identifier : ${node.getIdentifier} is already Retired.")
      node
    })
  }

  def getNodeWithExternalProps(request: Request)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
    val extPropNameList: util.List[String] = DefinitionNode.getExternalProps(request.getContext.get("graph_id").asInstanceOf[String], request.getContext.get("version").asInstanceOf[String], request.getContext.get("schemaName").asInstanceOf[String]).asJava
    val readReq = new Request(request)
    readReq.put("identifier", request.get("identifier").toString)
    readReq.put("mode", "edit")
    readReq.put("fields", extPropNameList)
    DataNode.read(readReq).map(node => node)
  }

  def validateQuestionNodeForReview(request: Request, node: Node)(implicit ec: ExecutionContext, oec: OntologyEngineContext): List[String] = {
    val messages = ListBuffer[String]()
    //TODO: Refactor this method to use schema level configuration to get all property which need to be validated during review and publish
    //TODO: Extract type of each property from schema and validate. Also resolve oneOf props
    val metadata = node.getMetadata
    if (StringUtils.isBlank(metadata.getOrDefault("body", "").asInstanceOf[String]))
      messages += s"""body"""
    /*if (StringUtils.isBlank(metadata.getOrDefault("answer", "").asInstanceOf[String]))
      messages += s"""answer"""*/
    if (null != metadata.get("interactionTypes")) {
      if (StringUtils.isBlank(metadata.getOrElse("responseDeclaration", "").asInstanceOf[String])) messages += s"""responseDeclaration"""
      if (StringUtils.isBlank(metadata.getOrElse("interactions", "").asInstanceOf[String])) messages += s"""interactions"""
      if (StringUtils.isBlank(metadata.getOrElse("outcomeDeclaration", "").asInstanceOf[String])) messages += s"""outcomeDeclaration"""
    } else {
      if (StringUtils.isBlank(metadata.getOrDefault("answer", "").asInstanceOf[String]))
        messages += s"""answer"""
    }
    messages.toList
  }

  def validateHierarchy(request: Request, children: util.List[util.Map[String, AnyRef]], rootUserId: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Unit = {
    children.toList.foreach(content => {
      val version: Double = content.getOrDefault("qumlVersion", 1.0.asInstanceOf[AnyRef]).asInstanceOf[Double]
      if (version < 1.1)
        throw new ClientException(AssessmentErrorCodes.ERR_OBJECT_VALIDATION, s"Children Object with identifier ${content.get("identifier").toString} doesn't have data in QuML 1.1 format.")
      if ((StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Default")
        && !StringUtils.equals(rootUserId, content.getOrDefault("createdBy", "").asInstanceOf[String]))
        && !StringUtils.equalsIgnoreCase(content.getOrDefault("status", "").asInstanceOf[String], "Live"))
        throw new ClientException(AssessmentErrorCodes.ERR_OBJECT_VALIDATION, "Object with identifier: " + content.get("identifier") + " is not Live. Please Publish it.")
      if ((StringUtils.equalsAnyIgnoreCase("application/vnd.sunbird.question", content.get("mimeType").toString) &&
        StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Parent")
        && !StringUtils.equalsIgnoreCase(content.getOrDefault("status", "").asInstanceOf[String], "Live"))
        || (StringUtils.equalsAnyIgnoreCase("application/vnd.sunbird.question", content.get("mimeType").toString)
        && StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Default")
        && StringUtils.equals(rootUserId, content.getOrDefault("createdBy", "").asInstanceOf[String])
        && !StringUtils.equalsIgnoreCase(content.getOrDefault("status", "").asInstanceOf[String], "Live"))) {
        val readReq = new Request(request)
        readReq.getRequest.put("identifier", content.get("identifier").toString)
        readReq.getContext.put("identifier", content.get("identifier").toString)
        readReq.getContext.put("objectType", content.get("objectType").toString)
        readReq.getContext.put("schemaName", content.get("objectType").toString.toLowerCase().replace("image", ""))
        readReq.getContext.put("version", content.getOrDefault("schemaVersion", "1.1").toString)
        readReq.put("mode", "edit")
        val extPropNameList: util.List[String] = DefinitionNode.getExternalProps(request.getContext.get("graph_id").asInstanceOf[String], content.getOrDefault("schemaVersion", "1.1").asInstanceOf[String], content.getOrDefault("objectType", "Question").asInstanceOf[String].toLowerCase.replace("image", "")).asJava
        readReq.put("fields", extPropNameList)
        val messages: List[String] = Await.result(DataNode.read(readReq).map(node => {
          val messages = validateQuestionNodeForReview(request, node)
          messages
        }), Duration.apply("30 seconds"))
        if (messages.nonEmpty)
          throw new ClientException(AssessmentErrorCodes.ERR_OBJECT_VALIDATION, s"Mandatory Fields ${messages.asJava} Missing for ${content.get("identifier").toString}")
      }
      val nestedChildren = content.getOrDefault("children", new util.ArrayList[Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]]
      if (!nestedChildren.isEmpty)
        validateHierarchy(request, nestedChildren, rootUserId)
    })
  }

  def updateHierarchy(hierarchy: util.Map[String, AnyRef], status: String, rootUserId: String): (java.util.Map[String, AnyRef], java.util.List[String]) = {
    val keys = List("identifier", "children").asJava
    hierarchy.keySet().retainAll(keys)
    val children = hierarchy.getOrDefault("children", new util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[util.List[java.util.Map[String, AnyRef]]]
    val childrenToUpdate: List[String] = updateChildrenRecursive(children, status, List(), rootUserId)
    (hierarchy, childrenToUpdate.asJava)
  }

  private def updateChildrenRecursive(children: util.List[util.Map[String, AnyRef]], status: String, idList: List[String], rootUserId: String): List[String] = {
    children.toList.flatMap(content => {
      val objectType = content.getOrDefault("objectType", "").asInstanceOf[String]
      val updatedIdList: List[String] =
        if (StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Parent") || (StringUtils.equalsIgnoreCase(objectType, "Question") && StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Default") && validStatus.contains(content.getOrDefault("status", "").asInstanceOf[String]) && StringUtils.equals(rootUserId, content.getOrDefault("createdBy", "").asInstanceOf[String]))) {
          content.put("lastStatusChangedOn", DateUtils.formatCurrentDate)
          content.put("prevStatus", content.getOrDefault("status", "Draft"))
          content.put("status", status)
          content.put("lastUpdatedOn", DateUtils.formatCurrentDate)
          if (StringUtils.equalsAnyIgnoreCase(objectType, "Question")) content.get("identifier").asInstanceOf[String] :: idList else idList
        } else idList
      val list = updateChildrenRecursive(content.getOrDefault("children", new util.ArrayList[Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]], status, updatedIdList, rootUserId)
      list ++ updatedIdList
    })
  }

  def getQuestionSetHierarchy(request: Request, rootNode: Node)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[String] = {
    request.put("rootId", request.get("identifier").asInstanceOf[String])
    HierarchyManager.getUnPublishedHierarchy(request).map(resp => {
      if (!ResponseHandler.checkError(resp) && resp.getResponseCode.code() == 200) {
        val hierarchy = resp.getResult.get("questionSet").asInstanceOf[util.Map[String, AnyRef]]
        JsonUtils.serialize(hierarchy)
      } else throw new ServerException("ERR_QUESTION_SET_HIERARCHY", "No hierarchy is present in cassandra for identifier:" + rootNode.getIdentifier)
    })
  }

  def getValidatedNodeForPublish(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
    request.put("mode", "edit")
    DataNode.read(request).map(node => {
      if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
        throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with visibility Parent, can't be sent for publish individually.")
      if (StringUtils.equalsAnyIgnoreCase(node.getMetadata.getOrDefault("status", "").asInstanceOf[String], "Processing"))
        throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} having Processing status can't be sent for publish.")
      val version: Double = node.getMetadata.getOrDefault("qumlVersion", 1.0.asInstanceOf[AnyRef]).asInstanceOf[Double]
      if (version < 1.1)
        throw new ClientException(errCode, s"${node.getObjectType().replace("Image", "")} can't be sent for publish as data is not in QuML 1.1 format.")
      node
    })
  }

  @throws[Exception]
  def pushInstructionEvent(identifier: String, node: Node)(implicit oec: OntologyEngineContext): Unit = {
    val (actor, context, objData, eData) = generateInstructionEventMetadata(identifier.replace(".img", ""), node)
    val beJobRequestEvent: String = LogTelemetryEventUtil.logInstructionEvent(actor.asJava, context.asJava, objData.asJava, eData)
    val topic: String = Platform.getString("kafka.topics.instruction", "sunbirddev.learning.job.request")
    if (StringUtils.isBlank(beJobRequestEvent)) throw new ClientException("BE_JOB_REQUEST_EXCEPTION", "Event is not generated properly.")
    oec.kafkaClient.send(beJobRequestEvent, topic)
  }

  def generateInstructionEventMetadata(identifier: String, node: Node): (Map[String, AnyRef], Map[String, AnyRef], Map[String, AnyRef], util.Map[String, AnyRef]) = {
    val metadata: util.Map[String, AnyRef] = node.getMetadata
    val publishType = if (StringUtils.equalsIgnoreCase(metadata.getOrDefault("status", "").asInstanceOf[String], "Unlisted")) "unlisted" else "public"
    val eventMetadata = Map("identifier" -> identifier, "mimeType" -> metadata.getOrDefault("mimeType", ""), "objectType" -> node.getObjectType.replace("Image", ""), "pkgVersion" -> metadata.getOrDefault("pkgVersion", 0.asInstanceOf[AnyRef]), "lastPublishedBy" -> metadata.getOrDefault("lastPublishedBy", ""), "qumlVersion" -> node.getMetadata.getOrDefault("qumlVersion", 1.1.asInstanceOf[AnyRef]), "schemaVersion" -> node.getMetadata.getOrDefault("schemaVersion", "1.1").asInstanceOf[String])
    val actor = Map("id" -> s"${node.getObjectType.toLowerCase().replace("image", "")}-publish", "type" -> "System".asInstanceOf[AnyRef])
    val context = Map("channel" -> metadata.getOrDefault("channel", ""), "pdata" -> Map("id" -> "org.sunbird.platform", "ver" -> "1.0").asJava, "env" -> Platform.getString("cloud_storage.env", "dev"))
    val objData = Map("id" -> identifier, "ver" -> metadata.getOrDefault("versionKey", ""))
    val eData: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef] {
      {
        put("action", "publish")
        put("publish_type", publishType)
        put("metadata", eventMetadata.asJava)
      }
    }
    (actor, context, objData, eData)
  }

  def getQuestionSetMetadata(node: Node, fields: util.List[String])(implicit oec: OntologyEngineContext, ec: ExecutionContext): util.Map[String, AnyRef] = {
    val version: AnyRef = node.getMetadata.getOrDefault("schemaVersion", 1.0.asInstanceOf[AnyRef])
    val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, List().asJava, node.getObjectType.toLowerCase.replace("Image", ""), version.toString)
    val updatedMeta = if (version.toString == "1.0") AssessmentV5Manager.getTransformedQuestionSetMetadata(metadata) else AssessmentV5Manager.convertOneOfProps(node, metadata)
    if (CollectionUtils.isNotEmpty(fields))
      updatedMeta.keySet.retainAll(fields)
    else updatedMeta.remove("outcomeDeclaration")
    updatedMeta.put("identifier", node.getIdentifier.replace(".img", ""))
    updatedMeta
  }

  def processMaxScore(data: util.Map[String, AnyRef]): Unit = {
    if (data.containsKey("maxScore")) {
      val maxScore = data.remove("maxScore")
      val outcomeDeclaration = Map("maxScore" -> Map("cardinality" -> "single", "type" -> "integer", "defaultValue" -> maxScore).asJava).asJava
      data.put("outcomeDeclaration", outcomeDeclaration)
    }
  }

  def processInstructions(data: util.Map[String, AnyRef]): Unit = {
    if (data.containsKey("instructions")) {
      val instructions = data.getOrDefault("instructions", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
      if (!instructions.isEmpty && (instructions.keySet().size() == 1 && instructions.keySet().contains("default"))) {
        data.put("instructions", instructions.get("default").asInstanceOf[String])
      }
    }
  }

  def processBloomsLevel(data: util.Map[String, AnyRef]): Unit = {
    if (data.containsKey("bloomsLevel")) {
      val bLevel = data.remove("bloomsLevel")
      data.put("complexityLevel", List(bLevel.toString).asJava)
    }
  }

  def processBooleanProps(data: util.Map[String, AnyRef]): Unit = {
    val booleanProps = List("showSolutions", "showFeedback", "showHints", "showTimer")
    booleanProps.foreach(prop => {
      if (data.containsKey(prop)) {
        val propVal: String = data.get(prop).asInstanceOf[String]
        data.put(prop, getBooleanValue(propVal).asInstanceOf[AnyRef])
      }
    })
  }

  def processTimeLimits(data: util.Map[String, AnyRef]): Unit = {
    if (data.containsKey("timeLimits")) {
      val timeLimits:util.Map[String, AnyRef] = if(data.get("timeLimits").isInstanceOf[util.Map[String, AnyRef]]) data.getOrDefault("timeLimits", Map().asJava).asInstanceOf[util.Map[String, AnyRef]] else JsonUtils.deserialize(data.get("timeLimits").asInstanceOf[String], classOf[java.util.Map[String, AnyRef]])
      val maxTime: Integer = timeLimits.getOrElse("maxTime", "0").asInstanceOf[String].toInt
      val updatedData: util.Map[String, AnyRef] = Map("questionSet" -> Map("max" -> maxTime, "min" -> 0.asInstanceOf[AnyRef]).asJava).asJava.asInstanceOf[util.Map[String, AnyRef]]
      data.put("timeLimits", updatedData)
    }
  }

  def getAnswer(data: util.Map[String, AnyRef]): String = {
    val interactions: util.Map[String, AnyRef] = data.getOrDefault("interactions", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]]
    if (!StringUtils.equalsIgnoreCase(data.getOrElse("primaryCategory", "").asInstanceOf[String], "Subjective Question") && !interactions.isEmpty) {
      val responseDeclaration: util.Map[String, AnyRef] = data.getOrDefault("responseDeclaration", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]]
      val responseData = responseDeclaration.get("response1").asInstanceOf[util.Map[String, AnyRef]]
      val intractionsResp1: util.Map[String, AnyRef] = interactions.getOrElse("response1", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
      val options = intractionsResp1.getOrElse("options", List().asJava).asInstanceOf[util.List[util.Map[String, AnyRef]]]
      val answerData = responseData.get("cardinality").asInstanceOf[String] match {
        case "single" => {
          val correctResp = responseData.getOrDefault("correctResponse", Map().asJava).asInstanceOf[util.Map[String, AnyRef]].get("value").asInstanceOf[Integer]
          val label = options.toList.filter(op => op.get("value").asInstanceOf[Integer] == correctResp).head.get("label").asInstanceOf[String]
          val answer = """<div class="anwser-container"><div class="anwser-body">answer_html</div></div>""".replace("answer_html", label)
          answer
        }
        case "multiple" => {
          val correctResp = responseData.getOrDefault("correctResponse", Map().asJava).asInstanceOf[util.Map[String, AnyRef]].get("value").asInstanceOf[util.List[Integer]]
          val singleAns = """<div class="anwser-body">answer_html</div>"""
          val answerList: List[String] = options.toList.filter(op => correctResp.contains(op.get("value").asInstanceOf[Integer])).map(op => singleAns.replace("answer_html", op.get("label").asInstanceOf[String])).toList
          val answer = """<div class="anwser-container">answer_div</div>""".replace("answer_div", answerList.mkString(""))
          answer
        }
      }
      answerData
    } else data.getOrDefault("answer", "").asInstanceOf[String]
  }

  def processInteractions(data: util.Map[String, AnyRef]): Unit = {
    val interactions: util.Map[String, AnyRef] = data.getOrDefault("interactions", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]]
    if (!interactions.isEmpty) {
      val validation = interactions.getOrElse("validation", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
      val resp1: util.Map[String, AnyRef] = interactions.getOrElse("response1", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
      val resValData = interactions.getOrElse("response1", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]].getOrElse("validation", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
      if (!resValData.isEmpty) resValData.putAll(validation) else resp1.put("validation", validation)
      interactions.remove("validation")
      interactions.put("response1", resp1)
      data.put("interactions", interactions)
    }
  }

  def processHints(data: util.Map[String, AnyRef]): Unit = {
    val hints = data.getOrDefault("hints", List[String]().asJava).asInstanceOf[util.List[String]]
    if (!hints.isEmpty) {
      val updatedHints: util.Map[String, AnyRef] = hints.toList.map(hint => Map[String, AnyRef](UUID.randomUUID().toString -> hint).asJava).flatten.toMap.asJava
      data.put("hints", updatedHints)
    }
  }

  def processResponseDeclaration(data: util.Map[String, AnyRef]): Unit = {
    val outcomeDeclaration = new util.HashMap[String, AnyRef]()
    // Remove responseDeclaration metadata for Subjective Question
    if (StringUtils.equalsIgnoreCase("Subjective Question", data.getOrDefault("primaryCategory", "").toString)) {
      data.remove("responseDeclaration")
      data.remove("interactions")
      if(data.containsKey("maxScore") && null != data.get("maxScore")) {
        data.put("outcomeDeclaration", Map[String, AnyRef]("cardinality" -> "single", "type" -> "integer", "defaultValue" -> data.get("maxScore")).asJava)
      }
    } else {
      //transform responseDeclaration and populate outcomeDeclaration
      val responseDeclaration: util.Map[String, AnyRef] = data.getOrDefault("responseDeclaration", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]]
      if (!responseDeclaration.isEmpty) {
        for (key <- responseDeclaration.keySet()) {
          val responseData = responseDeclaration.get(key).asInstanceOf[util.Map[String, AnyRef]]
          // remove maxScore and put it under outcomeDeclaration
          val maxScore = Map[String, AnyRef]("cardinality" -> responseData.getOrDefault("cardinality", "").asInstanceOf[String], "type" -> responseData.getOrDefault("type", "").asInstanceOf[String], "defaultValue" -> responseData.get("maxScore"))
          responseData.remove("maxScore")
          outcomeDeclaration.put("maxScore", maxScore.asJava)
          //remove outcome from correctResponse
          responseData.getOrDefault("correctResponse", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]].remove("outcomes")
          // type cast value. data type mismatch seen in quml 1.0 data where type and data was integer but integer data was populated as string
          try {
            if (StringUtils.equalsIgnoreCase("integer", responseData.getOrDefault("type", "").asInstanceOf[String])
              && StringUtils.equalsIgnoreCase("single", responseData.getOrDefault("cardinality", "").asInstanceOf[String])) {
              val correctResp: util.Map[String, AnyRef] = responseData.getOrDefault("correctResponse", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]]
              val correctKey: String = correctResp.getOrDefault("value", "0").asInstanceOf[String]
              correctResp.put("value", correctKey.toInt.asInstanceOf[AnyRef])
            }
          } catch {
            case e: NumberFormatException => e.printStackTrace()
          }
          //update mapping
          val mappingData = responseData.getOrElse("mapping", List[util.Map[String, AnyRef]]().asJava).asInstanceOf[util.List[util.Map[String, AnyRef]]]
          if (!mappingData.isEmpty) {
            val updatedMapping = mappingData.asScala.toList.map(mapData => {
              Map[String, AnyRef]("value" -> mapData.get("response"), "score" -> mapData.getOrDefault("outcomes", Map[String, AnyRef]().asJava).asInstanceOf[util.Map[String, AnyRef]].get("score")).asJava
            }).asJava
            responseData.put("mapping", updatedMapping)
          }
        }
        data.put("responseDeclaration", responseDeclaration)
        data.put("outcomeDeclaration", outcomeDeclaration)
      }
    }
  }

  def processSolutions(data: util.Map[String, AnyRef]): Unit = {
    val solutions = data.getOrDefault("solutions", List[util.Map[String, AnyRef]](Map[String, AnyRef]().asJava).asJava).asInstanceOf[util.List[util.Map[String, AnyRef]]]
    if (!solutions.isEmpty) {
      val updatedSolutions: util.Map[String, AnyRef] = solutions.asScala.toList.map(solution => {
        Map[String, AnyRef](solution.getOrElse("id", "").asInstanceOf[String] -> getSolutionString(solution, data.getOrElse("media", List[util.Map[String, AnyRef]](Map[String, AnyRef]().asJava).asJava).asInstanceOf[util.List[util.Map[String, AnyRef]]]))
      }).flatten.toMap.asJava
      data.put("solutions", updatedSolutions)
    }
  }

  def getBooleanValue(str: String): Boolean = {
    str match {
      case "Yes" => true
      case _ => false
    }
  }

  def getSolutionString(data: util.Map[String, AnyRef], media: util.List[util.Map[String, AnyRef]]): String = {
    if (!data.isEmpty) {
      data.getOrDefault("type", "").asInstanceOf[String] match {
        case "html" => data.getOrDefault("value", "").asInstanceOf[String]
        case "video" => {
          val value = data.getOrDefault("value", "").asInstanceOf[String]
          val mediaData: util.Map[String, AnyRef] = media.asScala.toList.filter(map => StringUtils.equalsIgnoreCase(value, map.getOrElse("id", "").asInstanceOf[String])).flatten.toMap.asJava
          val src = mediaData.getOrDefault("src", "").asInstanceOf[String]
          val thumbnail = mediaData.getOrDefault("thumbnail", "").asInstanceOf[String]
          val solutionStr = """<video data-asset-variable="media_identifier" width="400" controls="" poster="thumbnail_url"><source type="video/mp4" src="media_source_url"><source type="video/webm" src="media_source_url"></video>""".replace("media_identifier", value).replace("thumbnail_url", thumbnail).replace("media_source_url", src)
          solutionStr
        }
      }
    } else ""
  }

  def getTransformedQuestionMetadata(data: util.Map[String, AnyRef]): util.Map[String, AnyRef] = {
    try {
      if (!data.isEmpty) {
        processResponseDeclaration(data)
        processInteractions(data)
        processSolutions(data)
        processInstructions(data)
        processHints(data)
        processBloomsLevel(data)
        processBooleanProps(data)
        val ans = getAnswer(data)
        if (StringUtils.isNotBlank(ans))
          data.put("answer", ans)
        data
      } else data
    } catch {
      case e: Exception => {
        e.printStackTrace()
        throw new ServerException("ERR_QUML_DATA_TRANSFORM", s"Error Occurred While Converting Data To Quml 1.1 Format for ${data.get("identifier")}")
      }
    }
  }

  def getTransformedHierarchy(data: util.Map[String, AnyRef]): util.Map[String, AnyRef] = {
    val updatedMeta = getTransformedQuestionSetMetadata(data)
    updatedMeta.remove("outcomeDeclaration")
    val children = updatedMeta.getOrDefault("children", new util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[util.List[java.util.Map[String, AnyRef]]]
    if (!children.isEmpty)
      tranformChildren(children)
    updatedMeta
  }

  def tranformChildren(children: util.List[util.Map[String, AnyRef]]): Unit = {
    if (!children.isEmpty) {
      children.foreach(ch => {
        if (ch.containsKey("version")) ch.remove("version")
        processBloomsLevel(ch)
        processBooleanProps(ch)
        if (StringUtils.equalsIgnoreCase("application/vnd.sunbird.questionset", ch.getOrDefault("mimeType", "").asInstanceOf[String])) {
          processTimeLimits(ch)
          processInstructions(ch)
          val nestedChildren = ch.getOrDefault("children", new util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[util.List[java.util.Map[String, AnyRef]]]
          tranformChildren(nestedChildren)
        }
      })
    }
  }

  def getTransformedQuestionSetMetadata(data: util.Map[String, AnyRef]): util.Map[String, AnyRef] = {
    try {
      if (!data.isEmpty) {
        processMaxScore(data)
        data.remove("version")
        processInstructions(data)
        processBloomsLevel(data)
        processBooleanProps(data)
        processTimeLimits(data)
        data
      } else data
    } catch {
      case e: Exception => {
        e.printStackTrace()
        throw new ServerException("ERR_QUML_DATA_TRANSFORM", s"Error Occurred While Converting Data To Quml 1.1 Format for ${data.get("identifier")}")
      }
    }
  }

  def validateAssessRequest(req: Request) = {
    val body = req.getRequest
    val jwt = body.getOrDefault(AssessmentConstants.QUESTION_SET_TOKEN, "").asInstanceOf[String]
    val payload = try {
      val tuple = HierarchyManager.verifyRS256Token(jwt)
      if (tuple._1 == false)
        throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
      tuple._2.get("data").asInstanceOf[String]
    } catch {
      case e: Exception => throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
    }
    val questToken = JavaJsonUtils.deserialize[java.util.Map[String, AnyRef]](payload)
    val assessments = body.getOrDefault(AssessmentConstants.ASSESSMENTS, new util.ArrayList[util.Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]]
    val courseMetaData = Option(assessments.get(0)).getOrElse(new util.HashMap[String, AnyRef])
    val count = map.filter(key => StringUtils.equals(courseMetaData.get(key._1).asInstanceOf[String], questToken.get(key._2).asInstanceOf[String])).size
    if (count != map.size)
      throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
    questToken.get(AssessmentConstants.QUESTION_LIST).asInstanceOf[String].split(",").asInstanceOf[Array[String]]
  }

  def questionList(fields: Array[String]): Response = {
    val url: String = Platform.getString(AssessmentConstants.QUESTION_LIST_EDITOR_URL, "")
    val bdy = "{\"request\":{\"search\":{\"identifier\":" + JavaJsonUtils.serialize(fields) + "}}}"
    val httpResponse = post(url, bdy)
    if (200 != httpResponse.status) throw new ServerException("ERR_QUESTION_LIST_API_COMM", "Error communicating to question list api")
    JsonUtils.deserialize(httpResponse.body, classOf[Response])
  }

  def calculateScore(privateList: Response, assessments: util.List[util.Map[String, AnyRef]]): Unit = {
//    val answerMaps: (Map[String, AnyRef], Map[String, AnyRef]) = getListMap(privateList.getResult, AssessmentConstants.QUESTIONS)
//      .map { que =>
//        ((que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.RESPONSE_DECLARATION)),
//          (que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.EDITOR_STATE)))
//        )
//      }.unzip match {
//      case (map1, map2) => (map1.toMap, map2.toMap)
//    }
val answerMaps: (Map[String, AnyRef], Map[String, AnyRef], Map[String, AnyRef]) = {
  val listOfMaps = getListMap(privateList.getResult, AssessmentConstants.QUESTIONS)
    .map { que =>
      (
        que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.RESPONSE_DECLARATION),
        que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.EDITOR_STATE),
        que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.MAX_SCORE)
      )
    }
  val (map1, map2, map3) = (listOfMaps.map(_._1).toMap, listOfMaps.map(_._2).toMap, listOfMaps.map(_._3).toMap)
  (map1, map2, map3)
}
    val answerMap = answerMaps._1
    val editorStateMap = answerMaps._2
    val maxScoreMap = answerMaps._3
    assessments.foreach { k =>
      getListMap(k, AssessmentConstants.EVENTS).toList.foreach { event =>
        val edata = getMap(event, AssessmentConstants.EDATA)
        val item = getMap(edata, AssessmentConstants.ITEM)
        val identifier = item.getOrDefault(AssessmentConstants.ID, "").asInstanceOf[String]
        if (!answerMap.contains(identifier))
          throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Invalid Request")
        val res = getMap(answerMap.get(identifier).asInstanceOf[Some[util.Map[String, AnyRef]]].x, AssessmentConstants.RESPONSE1)
        val cardinality = res.getOrDefault(AssessmentConstants.CARDINALITY, "").asInstanceOf[String]
      //  val maxScore = res.getOrDefault(AssessmentConstants.MAX_SCORE, 0.asInstanceOf[Integer]).asInstanceOf[Integer]
        val maxScoreOption = maxScoreMap.get(identifier)
        val maxScore = maxScoreOption.getOrElse(0).asInstanceOf[Integer]
        cardinality match {
          case AssessmentConstants.MULTIPLE => populateMultiCardinality(res, edata, maxScore)
          case _ => populateSingleCardinality(res, edata, maxScore)
        }
        populateParams(item, editorStateMap)
      }
    }
  }

  private def getListMap(arg: util.Map[String, AnyRef], param: String) = {
    arg.getOrDefault(param, new util.ArrayList[util.Map[String, AnyRef]]()).asInstanceOf[util.ArrayList[util.Map[String, AnyRef]]]
  }

  private def getMap(arg: util.Map[String, AnyRef], param: String) = {
    arg.getOrDefault(param, new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
  }

  def hideEditorStateAns(node: Node): String = {
    // Modify editorState
    Option(node.getMetadata.get("editorState")) match {
      case Some(jsonStr: String) =>
        val jsonNode = mapper.readTree(jsonStr)
        //if (jsonNode != null && jsonNode.has("question")) {
        //val questionNode = jsonNode.get("question")
        if (jsonNode != null && jsonNode.has("options")) {
          val optionsNode = jsonNode.get("options").asInstanceOf[ArrayNode]
          val iterator = optionsNode.elements()
          while (iterator.hasNext) {
            val optionNode = iterator.next().asInstanceOf[ObjectNode]
            optionNode.remove("answer")
          }
          //}
        }
        mapper.writeValueAsString(jsonNode)
      case _ => ""
    }
  }

  def hideCorrectResponse(node: Node): String = {
    val responseDeclaration = Option(node.getMetadata.get("responseDeclaration")) match {
      case Some(jsonStr: String) => jsonStr
      case _ => ""
    }
    val jsonNode = mapper.readTree(responseDeclaration)
    if (null != jsonNode && jsonNode.has("response1")) {
      val responseNode = jsonNode.get("response1").asInstanceOf[ObjectNode]
      responseNode.remove("correctResponse")
      mapper.writeValueAsString(jsonNode)
    }
    else
      ""
  }

  private def populateParams(item: util.Map[String, AnyRef], editorState: Map[String, AnyRef]) = {
    item.put(AssessmentConstants.PARAMS, editorState.get(item.get(AssessmentConstants.ID)).asInstanceOf[util.Map[String, AnyRef]].get(AssessmentConstants.OPTIONS))
  }

  private def post(url: String, requestBody: String, headers: Map[String, String] = Map[String, String]("Content-Type" -> "application/json")): HTTPResponse = {
    val res = Unirest.post(url).headers(headers.asJava).body(requestBody).asString()
    HTTPResponse(res.getStatus, res.getBody)
  }

  private case class HTTPResponse(status: Int, body: String) extends Serializable

  private def populateSingleCardinality(res: util.Map[String, AnyRef], edata: util.Map[String, AnyRef], maxScore: Integer): Unit = {
    val correctValue = getMap(res, AssessmentConstants.CORRECT_RESPONSE).getOrDefault(AssessmentConstants.VALUE, new util.ArrayList[Integer]).toString
    val usrResponse = getListMap(edata, AssessmentConstants.RESVALUES).get(0).getOrDefault(AssessmentConstants.VALUE, "").toString
    StringUtils.equals(usrResponse, correctValue) match {
      case true => {
        edata.put(AssessmentConstants.SCORE, maxScore)
        edata.put(AssessmentConstants.PASS, AssessmentConstants.YES)
      }
      case _ => {
        edata.put(AssessmentConstants.SCORE, 0.asInstanceOf[Integer])
        edata.put(AssessmentConstants.PASS, AssessmentConstants.NO)
      }
    }
  }

  private def populateMultiCardinality(res: util.Map[String, AnyRef], edata: util.Map[String, AnyRef], maxScore: Integer) = {
    val correctValue = getMap(res, AssessmentConstants.CORRECT_RESPONSE).getOrDefault(AssessmentConstants.VALUE, new util.ArrayList[Integer]).asInstanceOf[util.ArrayList[Integer]].flatMap(k => List(k)).sorted
    val usrResponse = edata.getOrDefault(AssessmentConstants.RESVALUES, new util.ArrayList[util.ArrayList[util.Map[String, AnyRef]]]())
      .asInstanceOf[util.ArrayList[util.ArrayList[util.Map[String, AnyRef]]]]
      .flatMap(_.flatMap(res => List(res.getOrDefault(AssessmentConstants.VALUE, -1.asInstanceOf[Integer]).asInstanceOf[Integer]))).sorted
    correctValue.equals(usrResponse) match {
      case true => edata.put(AssessmentConstants.SCORE, maxScore)
      case _ => {
        var ttlScr = 0.0d
        getListMap(res, AssessmentConstants.MAPPING).foreach(k => if (usrResponse.contains(k.getOrDefault(AssessmentConstants.RESPONSE, -1.asInstanceOf[Integer]).asInstanceOf[Integer]))
          ttlScr += getMap(k, AssessmentConstants.OUTCOMES).get(AssessmentConstants.SCORE).asInstanceOf[Double])
        edata.put(AssessmentConstants.SCORE, ttlScr.asInstanceOf[AnyRef])
        if (ttlScr > 0) edata.put(AssessmentConstants.PASS, AssessmentConstants.YES) else edata.put(AssessmentConstants.PASS, AssessmentConstants.NO)
      }
    }
  }
}
