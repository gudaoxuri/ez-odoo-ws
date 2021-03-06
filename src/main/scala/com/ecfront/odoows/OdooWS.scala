package com.ecfront.odoows

import java.text.SimpleDateFormat
import java.util

import com.ecfront.common.{BeanHelper, JsonHelper}
import com.ecfront.odoows.helper.XmlRPCHelper
import com.typesafe.scalalogging.slf4j.LazyLogging

import scala.collection.JavaConversions._

/**
 * Odoo WS
 * @param url ws url
 * @param db 连接的db
 */
case class OdooWS(url: String, db: String) extends LazyLogging {

  private val rpc = new XmlRPCHelper(if (url.endsWith("/")) url.substring(0, url.length - 1) else url)
  //保存登录信息
  private val session = collection.mutable.Map[Int, LoginInfo]()
  val emptyMap = new java.util.HashMap[String, Any]()


  object common {

    def version: Map[String, Any] = rpc.request("/xmlrpc/2/common", "version", List()).asInstanceOf[java.util.HashMap[String, Object]].toMap

    def login(userName: String, password: String): Int = {
      val uid: Int = rpc.request("/xmlrpc/2/common", "authenticate", List(db, userName, password, emptyMap)).asInstanceOf[java.lang.Integer].toInt
      session += uid -> LoginInfo(uid, password)
      uid
    }

  }

  object model {

    /**
     * 创建模型，Note:由于odoo没有提供删除物理表的接口故重建模型时需要手工删除已存在的物理表
     */
    def create[M <: OdooModel](clazz: Class[M], uid: Int): Unit = {
      val odoo = OdooWS.getClassInfo(clazz)
      val existIds = record.findIds(List(List("model", "=", odoo.name)), "ir.model", uid)
      if (existIds.nonEmpty) {
        record.delete(existIds.head, "ir.model", uid)
      }
      val id = execute(XmlRPCHelper.toJavaList(List(new util.HashMap[String, Any]() {
        {
          put("name", odoo.label)
          put("model", odoo.name)
          put("state", "manual")
        }
      })), emptyMap, "ir.model", "create", uid).asInstanceOf[java.lang.Integer].toInt
      val textAnnFields = BeanHelper.findFieldAnnotations(clazz, Seq(classOf[OText])).map(_.fieldName)
      val selectionAnnFields = BeanHelper.findFieldAnnotations(clazz, Seq(classOf[OSelection])).map(_.fieldName)
      BeanHelper.findFields(clazz, OdooWS.filterNames).map {
        item =>
          new util.HashMap[String, Any]() {
            {
              put("model_id", id)
              put("name", item._1)
              put("ttype", item._2.toLowerCase match {
                case t if textAnnFields.contains(item._1) => "text"
                case t if selectionAnnFields.contains(item._1) => "selection"
                case t if t == "string" || t == "char" => "char"
                case t if t == "int" || t == "integer" || t == "long" || t == "short" => "integer"
                case t if t == "boolean" || t == "bool" => "boolean"
                case t if t == "double" => "float"
                case t if t.endsWith("date") => "datetime"
                case _ => throw new Exception("Not support type:" + item._2)
              })
              put("state", "manual")
              put("required", false)
            }
          }
      }.foreach {
        item =>
          //没有批量创建接口，只得一条条提交
          execute(XmlRPCHelper.toJavaList(List(item)), emptyMap, "ir.model.fields", "create", uid)
      }
    }

  }

  object record {

    def create[M <: OdooModel](record: M, clazz: Class[M], uid: Int): Int = doCreate(OdooWS.getValues(record, 0), OdooWS.getClassInfo(clazz).name, uid)

    def create(record: Map[String, Any], modeName: String, uid: Int): Int = doCreate(XmlRPCHelper.toJavaMap(record), modeName, uid)

    private def doCreate(record: util.Map[String, Any], modelName: String, uid: Int): Int =
      execute(XmlRPCHelper.toJavaList(List(record)), emptyMap, modelName, "create", uid).asInstanceOf[java.lang.Integer].toInt

    def update[M <: OdooModel](record: M, clazz: Class[M], uid: Int): Boolean = doUpdate(record.id, OdooWS.getValues(record, 1), OdooWS.getClassInfo(clazz).name, uid)

    def update(id: Int, record: Map[String, Any], modeName: String, uid: Int): Boolean = doUpdate(id, XmlRPCHelper.toJavaMap(record), modeName, uid)

    private def doUpdate(id: Int, record: util.Map[String, Any], modelName: String, uid: Int): Boolean =
      execute(XmlRPCHelper.toJavaList(List(new util.ArrayList[Integer]() {
        {
          add(id)
        }
      }, record)), emptyMap, modelName, "write", uid).asInstanceOf[java.lang.Boolean]


    def delete[M <: OdooModel](id: Int, clazz: Class[M], uid: Int): Unit = doDelete(id, OdooWS.getClassInfo(clazz).name, uid)

    def deleteAll[M <: OdooModel](clazz: Class[M], uid: Int): Unit = doDelete(0, OdooWS.getClassInfo(clazz).name, uid)

    def delete(id: Int, modeName: String, uid: Int): Unit = doDelete(id, modeName, uid)

    def deleteAll(modeName: String, uid: Int): Unit = doDelete(0, modeName, uid)

    private def doDelete(id: Int, modelName: String, uid: Int): Unit = {
      val delIds = new util.ArrayList[Integer]()
      if (id == 0) {
        //delete all
        delIds.addAll(findIds(List(), modelName, uid).map(_.asInstanceOf[Integer]))
      } else {
        delIds.add(id)
      }

    }

    def count[M <: OdooModel](conditions: List[Any], clazz: Class[M], uid: Int): Int = doCount(conditions, OdooWS.getClassInfo(clazz).name, uid)

    def count(conditions: List[Any], modeName: String, uid: Int): Int = doCount(conditions, modeName, uid)

    private def doCount(conditions: List[Any], modelName: String, uid: Int): Int =
      execute(OdooWS.packageConditions(conditions), emptyMap, modelName, "search_count", uid).asInstanceOf[Integer].toInt

    def get[M <: OdooModel](id: Int, clazz: Class[M], uid: Int): M = OdooWS.toObject(doGet(id, OdooWS.getClassInfo(clazz).name, uid), clazz)

    def get(id: Int, modeName: String, uid: Int): Map[String, Any] = doGet(id, modeName, uid).toMap

    private def doGet(id: Int, modelName: String, uid: Int): util.Map[String, Any] = {
      execute(XmlRPCHelper.toJavaList(List(id)), emptyMap, modelName, "read", uid).asInstanceOf[util.HashMap[String, Any]]
    }

    def find[M <: OdooModel](conditions: List[Any], clazz: Class[M], uid: Int): List[M] = doFind(conditions, OdooWS.getClassInfo(clazz).name, uid).map(OdooWS.toObject(_, clazz))

    def find(conditions: List[Any], modeName: String, uid: Int): List[Map[String, Any]] = doFind(conditions, modeName, uid).map(_.asInstanceOf[util.HashMap[String, Any]].toMap)

    private def doFind(conditions: List[Any], modelName: String, uid: Int): List[Any] = {
      execute(OdooWS.packageConditions(conditions), emptyMap, modelName, "search_read", uid).asInstanceOf[Array[Any]].toList
    }

    def page[M <: OdooModel](pageNumber: Int, pageSize: Int, conditions: List[Any], clazz: Class[M], uid: Int): PageModel[M] =
      doPage(pageNumber, pageSize, conditions, OdooWS.getClassInfo(clazz).name, clazz, uid)

    def page(pageNumber: Int, pageSize: Int, conditions: List[Any], modeName: String, uid: Int): PageModel[Map[String, Any]] =
      doPage(pageNumber, pageSize, conditions, modeName, classOf[Map[String, Any]], uid)

    private def doPage[M](pageNumber: Int, pageSize: Int, conditions: List[Any], modelName: String, clazz: Class[M], uid: Int): PageModel[M] = {
      val recordTotal = count(conditions, modelName, uid)
      val result = execute(OdooWS.packageConditions(conditions), new util.HashMap[String, Any]() {
        {
          put("offset", pageSize * (pageNumber - 1))
          put("limit", pageSize)
        }
      }, modelName, "search_read", uid).asInstanceOf[Array[Any]].toList.map(OdooWS.toObject(_, clazz))
      PageModel[M](pageNumber, pageSize, (recordTotal + pageSize - 1) / pageSize, recordTotal, result)
    }

    def findIds[M <: OdooModel](conditions: List[Any], clazz: Class[M], uid: Int): List[Int] = doFindIds(conditions, OdooWS.getClassInfo(clazz).name, uid)

    def findIds(conditions: List[Any], modelName: String, uid: Int): List[Int] = doFindIds(conditions, modelName, uid)

    private def doFindIds(conditions: List[Any], modelName: String, uid: Int): List[Int] =
      execute(OdooWS.packageConditions(conditions), emptyMap, modelName, "search", uid).asInstanceOf[Array[Any]].toList.map(_.asInstanceOf[Int])

    def pageIds[M <: OdooModel](pageNumber: Int, pageSize: Int, conditions: List[Any], clazz: Class[M], uid: Int): List[Int] =
      doPageIds(pageNumber, pageSize, conditions, OdooWS.getClassInfo(clazz).name, uid)

    def pageIds(pageNumber: Int, pageSize: Int, conditions: List[Any], modelName: String, uid: Int): List[Int] =
      doPageIds(pageNumber, pageSize, conditions, modelName, uid)

    private def doPageIds(pageNumber: Int, pageSize: Int, conditions: List[Any], modelName: String, uid: Int): List[Int] =
      execute(OdooWS.packageConditions(conditions), new util.HashMap[String, Any]() {
        {
          put("offset", pageSize * (pageNumber - 1))
          put("limit", pageSize)
        }
      }, modelName, "search", uid).asInstanceOf[Array[Any]].toList.map(_.asInstanceOf[Int])
  }

  def execute(condition: util.ArrayList[Any], parameters: util.HashMap[String, Any], modelName: String, methodName: String, uid: Int): Any =
    rpc.request("/xmlrpc/2/object", "execute_kw",
      List(db, uid, session(uid).password, modelName, methodName,
        condition, parameters))

  def execute[E](condition: util.ArrayList[Any], parameters: util.HashMap[String, Any], modelName: String, methodName: String, uid: Int,clazz:Class[E]): E = {
    val result = rpc.request("/xmlrpc/2/object", "execute_kw",
      List(db, uid, session(uid).password, modelName, methodName,
        condition, parameters))
    OdooWS.toObject(result,clazz)
  }
}


object OdooWS {

  private val filterNames = Seq("id", "display_name", "create_uid", "create_date", "write_uid", "write_date")
  //className -> (fieldName -> (fieldType,annotation list))
  private val fieldInfoCache = collection.mutable.Map[String, Map[String, (String, Seq[Any])]]()
  //className -> odoo object
  private val clazzInfoCache = collection.mutable.Map[String, Odoo]()
  //Odoo的Datetime以String形式表达，需要按yyyy-MM-dd hh:mm:ss格式转换
  private val dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd")

  private def toObject[E](obj: Any, clazz: Class[E]): E = {
    obj match {
      case o: util.HashMap[String, Any] if clazz == classOf[Map[String, Any]] =>
        o.toMap.asInstanceOf[E]
      case o: util.HashMap[String, Any] if clazz != classOf[Map[String, Any]] =>
        val fields = geFieldsInfo(clazz)
        val value = o.filter(item => fields.contains(item._1)).map {
          item =>
            item._1 match {
              case key if fields(key)._2.exists(_.getClass == classOf[OManyToOne]) =>
                item._2 match {
                  case v: Array[Object] =>
                    item._1 -> v(0)
                  case _ =>
                    item._1 -> 0
                }
              case key if fields(key)._2.exists(_.getClass == classOf[OSelection]) =>
                item._2 match {
                  case v: Boolean =>
                    item._1 -> null
                  case _ =>
                    item._1 -> item._2
                }
              case key if fields(key)._2.exists(_.getClass == classOf[ODate]) =>
                item._2 match {
                  case v: String =>
                    //Odoo中的Date类型，精确到日期
                    item._1 -> dateFormat.parse(v)
                  case _ =>
                    //当表中 Date / DateTime 为空返回的竟然是false，无语……，这里需要转成null
                    item._1 -> null
                }
              case key if fields(key)._1 == "java.util.Date" =>
                item._2 match {
                  case v: String =>
                    //Odoo中的DateTime类型，精确到秒
                    item._1 -> dateTimeFormat.parse(v)
                  case _ =>
                    //同上
                    item._1 -> null
                }
              case key if item._2.getClass == classOf[java.lang.Boolean] =>
                fields(key)._1 match {
                  case "Int" => item._1 -> 0
                  case "Long" => item._1 -> 0
                  case "Short" => item._1 -> 0
                  case "Float" => item._1 -> 0.0
                  case "Double" => item._1 -> 0.0
                  case _ => item._1 -> item._2
                }
              case _ => item._1 -> item._2
            }
        }
        JsonHelper.toObject(value, clazz)
      case _ => JsonHelper.toObject(obj, clazz)
    }
  }

  def getValues[M <: OdooModel](record: M, model: Int): util.Map[String, Any] = {
    val fields = geFieldsInfo(record.getClass)
    XmlRPCHelper.toJavaMap(BeanHelper.findValues(record, OdooWS.filterNames).filter {
      item =>
        item._2 != null &&
          !fields(item._1)._2.exists(_.getClass == classOf[OTransient]) &&
          (!fields(item._1)._2.exists(_.getClass == classOf[OManyToOne]) || fields(item._1)._2.exists(_.getClass == classOf[OManyToOne]) && item._2 != 0)
    }.map {
      item =>
        item._1 match {
          case key if fields(key)._2.exists(t => t.getClass == classOf[OManyToMany] || t.getClass == classOf[OOneToMany]) =>
            val array = new util.ArrayList[Any]()
            model match {
              case 0 =>
                item._2.asInstanceOf[List[OdooModel]].foreach {
                  m =>
                    array.add(new util.ArrayList[Any]() {
                      {
                        add(0)
                        add(0)
                        add(OdooWS.getValues(m, 0))
                      }
                    })
                }
                item._1 -> array
              case 1 =>
                //TODO 暂时只支持这种更新
                item._1 -> new util.ArrayList[Any]() {
                  {
                    add(new util.ArrayList[Any]() {
                      {
                        add(6)
                        add(0)
                        add(XmlRPCHelper.toJavaList(item._2.asInstanceOf[List[Int]]))
                      }
                    })
                  }
                }
            }
          case key if fields(key)._2.exists(_.getClass == classOf[ODate]) =>
            item._2 match {
              case v: util.Date =>
                item._1 -> dateFormat.format(v)
              case _ =>
                item._1 -> null
            }
          case key if fields(key)._1 == "java.util.Date" =>
            item._2 match {
              case v: util.Date =>
                item._1 -> dateTimeFormat.format(v)
              case _ =>
                //同上
                item._1 -> null
            }
          case _ => item._1 -> item._2
        }
    })
  }

  private def getClassInfo(clazz: Class[_]): Odoo = {
    var odoo: Odoo = null
    if (clazzInfoCache.contains(clazz.getName)) {
      odoo = clazzInfoCache(clazz.getName)
    } else {
      odoo = BeanHelper.getClassAnnotation[Odoo](clazz).get
      clazzInfoCache += clazz.getName -> odoo
    }
    odoo
  }

  private def geFieldsInfo(clazz: Class[_]): Map[String, (String, Seq[Any])] = {
    if (!fieldInfoCache.contains(clazz.getName)) {
      val fields = BeanHelper.findFields(clazz)
      val annotations = BeanHelper.findFieldAnnotations(clazz)
      val fieldInfos = collection.mutable.Map[String, (String, Seq[Any])]()
      fields.foreach {
        f =>
          fieldInfos += f._1 ->(f._2, annotations.filter(_.fieldName == f._1).map(_.annotation))
      }
      fieldInfoCache += clazz.getName -> fieldInfos.toMap
    }
    fieldInfoCache(clazz.getName)
  }


  private def packageConditions(conditions: List[Any]) = {
    val cond = new util.ArrayList[Any]
    cond.add(XmlRPCHelper.toJavaLists(conditions))
    cond
  }
}
