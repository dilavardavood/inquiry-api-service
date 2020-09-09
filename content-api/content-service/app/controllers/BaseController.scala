package controllers


import java.io.File
import java.util
import java.util.UUID

import akka.actor.ActorRef
import akka.pattern.Patterns
import org.apache.commons.lang3.StringUtils
import org.sunbird.common.{DateUtils, Platform}
import org.sunbird.common.dto.{Response, ResponseHandler}
import org.sunbird.common.exception.{ClientException, ResponseCode}
import play.api.mvc._
import utils.JavaJsonUtils

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

abstract class BaseController(protected val cc: ControllerComponents)(implicit exec: ExecutionContext) extends AbstractController(cc) {
    val categoryMap: java.util.Map[String, AnyRef] = Platform.getAnyRef("contentTypeToPrimaryCategory",
        new util.HashMap[String, AnyRef]()).asInstanceOf[java.util.Map[String, AnyRef]]
    val categoryMapForMimeType: java.util.Map[String, AnyRef] = Platform.getAnyRef("mimeTypeToPrimaryCategory",
        new util.HashMap[String, AnyRef]()).asInstanceOf[java.util.Map[String, AnyRef]]
    val categoryMapForResourceType: java.util.Map[String, AnyRef] = Platform.getAnyRef("resourceTypeToPrimaryCategory",
        new util.HashMap[String, AnyRef]()).asInstanceOf[java.util.Map[String, AnyRef]]

    def requestBody()(implicit request: Request[AnyContent]) = {
        val body = request.body.asJson.getOrElse("{}").toString
        JavaJsonUtils.deserialize[java.util.Map[String, Object]](body).getOrDefault("request", new java.util.HashMap()).asInstanceOf[java.util.Map[String, Object]]
    }

    def requestFormData()(implicit request: Request[AnyContent]) = {
        val reqMap = new util.HashMap[String, AnyRef]()
        if(!request.body.asMultipartFormData.isEmpty) {
            val multipartData = request.body.asMultipartFormData.get
            if (null != multipartData.asFormUrlEncoded && !multipartData.asFormUrlEncoded.isEmpty) {
                if(multipartData.asFormUrlEncoded.getOrElse("fileUrl",Seq()).length > 0){
                    val fileUrl: String = multipartData.asFormUrlEncoded.getOrElse("fileUrl",Seq()).head
                    if (StringUtils.isNotBlank(fileUrl))
                        reqMap.put("fileUrl", fileUrl)
                }
                if(multipartData.asFormUrlEncoded.getOrElse("filePath",Seq()).length > 0){
                    val filePath: String = multipartData.asFormUrlEncoded.getOrElse("filePath",Seq()).head
                    if (StringUtils.isNotBlank(filePath))
                        reqMap.put("filePath", filePath)
                }
            }
            if (null != multipartData.files && !multipartData.files.isEmpty) {
                val file: File = new File("/tmp" + File.separator + request.body.asMultipartFormData.get.files.head.filename)
                multipartData.files.head.ref.copyTo(file, false)
                reqMap.put("file", file)
            }
        }
        if(StringUtils.isNotBlank(reqMap.getOrDefault("fileUrl", "").asInstanceOf[String]) || null != reqMap.get("file").asInstanceOf[File]){
            reqMap
        } else {
            throw new ClientException("ERR_INVALID_DATA", "Please Provide Valid File Or File Url!")
        }
    }

    def commonHeaders(ignoreHeaders: Option[List[String]] = Option(List()))(implicit request: Request[AnyContent]): java.util.Map[String, Object] = {
        val customHeaders = Map("x-channel-id" -> "channel", "X-Consumer-ID" -> "consumerId", "X-App-Id" -> "appId").filterKeys(key => !ignoreHeaders.getOrElse(List()).contains(key))
        customHeaders.map(ch => {
            val value = request.headers.get(ch._1)
            if (value.isDefined && !value.isEmpty) {
                collection.mutable.HashMap[String, Object](ch._2 -> value.get).asJava
            } else {
                collection.mutable.HashMap[String, Object]().asJava
            }
        }).reduce((a, b) => {
            a.putAll(b)
            return a
        })
    }

    def getRequest(input: java.util.Map[String, AnyRef], context: java.util.Map[String, AnyRef], operation: String, categoryMapping: Boolean = false): org.sunbird.common.dto.Request = {
        //Todo mapping and reverse mapping
        if (categoryMapping) setContentAndCategoryTypes(input)
        new org.sunbird.common.dto.Request(context, input, operation, null);
    }

    def getResult(apiId: String, actor: ActorRef, request: org.sunbird.common.dto.Request, categoryMapping: Boolean = false) : Future[Result] = {
        val future = Patterns.ask(actor, request, 30000) recoverWith {case e: Exception => Future(ResponseHandler.getErrorResponse(e))}
        future.map(f => {
            val result = f.asInstanceOf[Response]
            result.setId(apiId)
            setResponseEnvelope(result)
            if (categoryMapping && result.getResponseCode == ResponseCode.OK) setContentAndCategoryTypes(result.getResult.getOrDefault("content", new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]])
            val response = JavaJsonUtils.serialize(result);
            result.getResponseCode match {
                case ResponseCode.OK => Ok(response).as("application/json")
                case ResponseCode.CLIENT_ERROR => BadRequest(response).as("application/json")
                case ResponseCode.RESOURCE_NOT_FOUND => NotFound(response).as("application/json")
                case ResponseCode.PARTIAL_SUCCESS => MultiStatus(response).as("application/json")
                case _ => play.api.mvc.Results.InternalServerError(response).as("application/json")
            }
        })
    }

    def setResponseEnvelope(response: Response) = {
        response.setTs(DateUtils.formatCurrentDate("yyyy-MM-dd'T'HH:mm:ss'Z'XXX"))
        response.getParams.setResmsgid(UUID.randomUUID().toString)
    }

    def setRequestContext(request:org.sunbird.common.dto.Request, version: String, objectType: String, schemaName: String): Unit = {
        var contextMap: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef](){{
            put("graph_id", "domain")
            put("version" , version)
            put("objectType" , objectType)
            put("schemaName", schemaName)
        }};
        contextMap.putAll(request.getContext)
        request.setObjectType(objectType);
        request.setContext(contextMap)
    }

    private def setContentAndCategoryTypes(input: java.util.Map[String, AnyRef]): Unit = {
        val contentType = input.get("contentType").asInstanceOf[String]
        val primaryCategory = input.get("primaryCategory").asInstanceOf[String]
            val (updatedContentType, updatedPrimaryCategory): (String, String) = (contentType, primaryCategory) match {
                case (x: String, y: String) => (x, y)
                case ("Resource", y) => (contentType, getCategoryForResource(input.getOrDefault("mimeType", "").asInstanceOf[String],
                    input.getOrDefault("resourceType", "").asInstanceOf[String]))
                case (x: String, y) => (x, categoryMap.get(x).asInstanceOf[String])
                case (x, y: String) => (categoryMap.asScala.filter(entry => StringUtils.equalsIgnoreCase(entry._2.asInstanceOf[String], y)).keys.headOption.getOrElse(""), y)
                case _ => (contentType, primaryCategory)
            }
            input.put("contentType", updatedContentType)
            input.put("primaryCategory", updatedPrimaryCategory)
    }

    private def getCategoryForResource(mimeType: String, resourceType: String): String = (mimeType, resourceType) match {
        case ("", "") => "Learning Resource"
        case (x: String, y: String) => categoryMapForResourceType.getOrDefault(y, "Learning Resource").asInstanceOf[String]
        case (x: String, "") => categoryMapForMimeType.get(x).asInstanceOf[util.List[String]].asScala.headOption.getOrElse("Learning Resource")
        case _ => "Learning Resource"
    }

}
