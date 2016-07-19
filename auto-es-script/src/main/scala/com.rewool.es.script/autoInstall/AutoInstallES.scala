package com.rewool.es.script.autoInstall

import java.io.{PrintWriter, FileOutputStream, File, FileInputStream}
import java.util.Properties
import scala.io.Source
import scala.sys.process._
import scala.language.postfixOps

/**
  * Desc: 自动配置ES集群
  * ------------------------------------
  * Author:hanzhankang@gmail.com
  * Date:16/7/12
  * Time:下午11:20
  */
case class HostIp(name: String, port: Int) {
  override def toString: String = name + ":" + port
}

object AutoInstallES extends App {

  //读取配置文件
  val properties = new Properties()
  val path = Thread.currentThread().getContextClassLoader.getResource("conf/elasticsearch-cluster-list.conf").getPath
  properties.load(new FileInputStream(path))
  //master 列表
  val masterList = toStringArr(properties.getProperty("master.list"))
  println("master集群:" + properties.getProperty("master.list"))
  masterList.foreach(println)

  //slave 列表
  val slaveList = toStringArr(properties.getProperty("slave.list")) //master列表
  println("slave集群:" + properties.getProperty("slave.list"))
  slaveList.foreach(println)

  val combineHosts = masterList ++ slaveList
  masterList.foreach(f => install(f, "master", properties, filterHost(f, combineHosts)))
  slaveList.foreach(f => install(f, "slave", properties, filterHost(f, combineHosts)))


  def install(hostIp: HostIp, role: String, clusterProperties: Properties, filteredNodes: String) = {
    println(s"正在为主机 ${hostIp} 配置ES...")

    val clusterName = properties.getProperty("cluster.name")
    println("clusterName:" + clusterName)
    val outPath = properties.getProperty("out.path") //配置完成后的输出目录
    mkFile(outPath, false)
    println("outPath:" + outPath)
    val instalFilePath = properties.getProperty("install.file.path") //解压后,可直接安装的文件路径
    println("install.file.path:" + instalFilePath)

    val elasticsearchDirName = new File(instalFilePath).getName

    mkFile(s"${outPath}/${hostIp.name}", false)
    s"cp -r ${instalFilePath} ${outPath}/${hostIp.name}/" !
    val ymlConf = Thread.currentThread().getContextClassLoader.getResource(s"conf/${role}-elasticsearch.yml.conf").getPath

    //ES home
    val esHome = s"${outPath}/${hostIp.name}/${elasticsearchDirName}"
    val esName = new File(esHome).getName
    val yumPath = s"${esHome}/config/elasticsearch.yml"
    println("yumPath:" + yumPath)

    s"cp ${ymlConf} ${yumPath}" !

    val ymlPro = new Properties()
    ymlPro.load(new FileInputStream(yumPath))
    ymlPro.setProperty("cluster.name", clusterProperties.getProperty("cluster.name"))
    ymlPro.setProperty("node.name", hostIp.name)
    ymlPro.setProperty("transport.tcp.port", hostIp.port.toString)
    ymlPro.setProperty("http.port", (hostIp.port - 100).toString)
    ymlPro.setProperty("http.enabled", "true")

    //线设置为空
    //ymlPro.setProperty("discovery.zen.ping.unicast.hosts", filteredNodes)
    ymlPro.setProperty("discovery.zen.ping.unicast.hosts", "[]")


    println(s"filteredNodes:${filteredNodes}")

    val out = new FileOutputStream(yumPath);
    ymlPro.store(out, "")
    out.flush()

    val pw = new PrintWriter(yumPath + "_temp")
    val linesRecord = Source.fromFile(yumPath).getLines()
    linesRecord.foreach(line => {
      val temp = line.replace("=", " : ").replace("\\", "")
      pw.write(temp + "\n")
    })
    pw.flush()
    pw.close()
    s"cp  ${yumPath + "_temp"} ${yumPath}" !

    //如果是slave,需要追加扩展分词配置并移动文件
    if (role == "slave") {
      val extendConf = Thread.currentThread().getContextClassLoader.getResource("conf/slave_extend.yml.conf").getPath

      val pw = new PrintWriter(yumPath + "_temp_extend")

      val mainRecord = Source.fromFile(yumPath + "_temp").getLines()
      mainRecord.foreach(line => pw.write(line + "\n"))

      val extendRecord = Source.fromFile(extendConf).getLines()
      extendRecord.foreach(line => pw.write(line + "\n"))
      pw.flush()
      pw.close()

      s"mv ${yumPath + "_temp_extend"}  ${yumPath} " !

      new File(yumPath + "_temp_extend").delete()
    }

    new File(yumPath + "_temp").delete()


    //move elasticsearch.in.sh
    val inShFile = Thread.currentThread().getContextClassLoader.getResource(s"conf/${role}-elasticsearch.in.sh").getPath
    s"cp -r ${inShFile} ${esHome}/bin/" !

    if (clusterProperties.getProperty("install.plugin.head") == "true") {
      //安装head插件
      //s"${esHome}/bin/plugin install mobz/elasticsearch-head" !

      val headDir = Thread.currentThread().getContextClassLoader.getResource("conf/head").getPath
      s"cp -r ${headDir}  ${esHome}/plugins/  " !

      //查看head请访问:http://ip:port/_plugin/head/
    }
    if (clusterProperties.getProperty("install.plugin.ik") == "true") {
      //s"${esHome}/bin/plugin install medcl/elasticsearch-analysis-ik" !
      /**/

      mkFile(s"${esHome}/plugins", false)
      //安装IK分词插件
      val ikDir = Thread.currentThread().getContextClassLoader.getResource("conf/ik").getPath
      s"cp -r ${ikDir}  ${esHome}/plugins/  " !
    }

    //配置docker

    val dockerFileConf = Thread.currentThread().getContextClassLoader.getResource("conf/Dockerfile").getPath
    val dockerFileDestPath = new File(esHome).getParentFile.getAbsolutePath + File.separator + "Dockerfile"
    val dockerFilePW = new PrintWriter(dockerFileDestPath)
    Source.fromFile(dockerFileConf).getLines().foreach(line => {
      if (null != line) {
        val temp = if (line.contains("{es_tcp_port}")) {
          line.replace("{es_tcp_port}", hostIp.port.toString)
        } else if (line.contains("{es_http_port}")) {
          line.replace("{es_http_port}", (hostIp.port - 100).toString)
        } else if (line.contains("{es_name}")) {
          line.replace("{es_name}", esName)
        } else {
          line
        }
        dockerFilePW.write(temp + "\n")
      }
    })
    dockerFilePW.flush()
    dockerFilePW.close()


    println(s"主机 ${hostIp} 配置ES 完成.")
  }


  /**
    * 把形如 ["master01:9300","master02:9300"] 的配置解析为 Array[HostIp]
    *
    * @param str
    * @return
    */
  def toStringArr(str: String): Array[HostIp] = {
    if (null != str && str != "" && str.contains("[") && str.contains("]")) {
      val arr = str.substring(1, str.length - 1).split(",")
      for (i <- arr if null != i && i.length > 3 && i.contains(":")) yield {
        if (i.startsWith("""""")) {
          val temp = i.substring(1, i.length - 1).split(":")
          new HostIp(temp(0), temp(1).toInt)
        } else {
          val temp = i.split(":")
          new HostIp(temp(0), temp(1).toInt)
        }
      }
    } else {
      null
    }
  }


  /**
    * 创建文件或目录
    *
    * @param path
    * @param isFile
    * @return
    */
  def mkFile(path: String, isFile: Boolean) = {
    if (null == path || path.length < 2 || !path.contains(File.separator)) {
      throw new IllegalArgumentException(s"path=${path} 不是一个合法有效的路径")
    }
    if (isFile) {
      val selfFile = new File(path)
      if (null == selfFile.getParentFile) {
        s"mkdir -p ${selfFile.getParentFile.getAbsolutePath}" !
      }
      if (!selfFile.exists()) {
        s"touch ${path}" !
      }
    } else {
      if (!new File(path).exists()) {
        s"mkdir -p ${path}" !
      }
    }
  }

  /**
    * 过滤自身的host
    *
    * @param hostIp
    * @param list
    * @return
    */
  def filterHost(hostIp: HostIp, list: Array[HostIp]): String = {
    var temp: String = "["
    list.foreach(p => {
      if (p.name != hostIp.name)
        temp +=s""""${p}","""
    })
    if (temp.length > 2) {
      temp.substring(0, temp.length - 1) + "]"
    } else {
      "]"
    }
  }
}
