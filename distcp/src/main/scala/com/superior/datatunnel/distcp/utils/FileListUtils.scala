package com.superior.datatunnel.distcp.utils

import com.superior.datatunnel.api.model.DistCpOption
import com.superior.datatunnel.distcp.HdfsDistCpAction.KeyedCopyDefinition

import java.net.URI
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import org.apache.hadoop.fs.{FileSystem, Path, RemoteIterator}
import org.apache.spark.SparkContext
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import com.superior.datatunnel.distcp.objects._
import org.apache.commons.lang3.StringUtils

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try
import scala.util.matching.Regex
import scala.collection.JavaConverters._

object FileListUtils extends Logging {

  /** Turn a [[RemoteIterator]] into a Scala [[Iterator]]
    */
  private implicit class ScalaRemoteIterator[T](underlying: RemoteIterator[T]) extends Iterator[T] {
    override def hasNext: Boolean = underlying.hasNext

    override def next(): T = underlying.next()
  }

  private def pathMatches(path: Path, filters: List[Regex]): Boolean = {
    filters.exists(_.findFirstIn(path.toString).isDefined)
  }

  /** Recursively list files in a given directory on a given FileSystem. This will be done in parallel depending on the
    * value of `threads`. An optional list of regex filters to filter out files can be given.
    *
    * @param fs
    *   FileSystem to search
    * @param path
    *   Root path to search from
    * @param threads
    *   Number of threads to search in parallel
    * @param includePathRootInDependents
    *   Whether to include the root path `path` in the search output
    * @param includes
    *   A list of regex filters that will select only results that match one or more of the filters
    * @param excludes
    *   A list of regex filters that will filter out any results that match one or more of the filters
    */
  def listFiles(
      fs: FileSystem,
      path: Path,
      threads: Int,
      includePathRootInDependents: Boolean,
      includes: List[Regex],
      excludes: List[Regex],
      excludeHiddenFile: Boolean
  ): Seq[(SerializableFileStatus, Seq[SerializableFileStatus])] = {

    assert(threads > 0, "Number of threads must be positive")

    val maybePathRoot =
      if (includePathRootInDependents)
        Some(SerializableFileStatus(fs.getFileStatus(path)))
      else None

    val processed = new java.util.concurrent.LinkedBlockingQueue[
      (SerializableFileStatus, Seq[SerializableFileStatus])
    ](maybePathRoot.map((_, Seq.empty)).toSeq.asJava)
    val toProcess = new java.util.concurrent.LinkedBlockingDeque[
      (Path, Seq[SerializableFileStatus])
    ](List((path, maybePathRoot.toSeq)).asJava)
    val exceptions = new java.util.concurrent.ConcurrentLinkedQueue[Exception]()
    val threadsWorking = new ConcurrentHashMap[UUID, Boolean]()

    class FileLister extends Runnable {

      private val localFS = FileSystem.get(fs.getUri, fs.getConf)

      private val uuid = UUID.randomUUID()
      threadsWorking.put(uuid, true)

      override def run(): Unit = {
        while (threadsWorking.containsValue(true)) {
          Try(
            Option(toProcess.pollFirst(50, TimeUnit.MILLISECONDS))
          ).toOption.flatten match {
            case None =>
              threadsWorking.put(uuid, false)
            case Some(p) =>
              logDebug(
                s"Thread [$uuid] searching [${p._1}], waiting to process depth [${toProcess.size()}]"
              )
              threadsWorking.put(uuid, true)
              try {
                var files = localFS.listLocatedStatus(p._1).toSeq
                if (excludeHiddenFile) {
                  files = files.filter(file => !StringUtils.startsWith(file.getPath.getName, "."))
                }

                files.foreach {
                  case l if l.isSymlink =>
                    throw new RuntimeException(s"Link [$l] is not supported")
                  case d if d.isDirectory =>
                    if (!pathMatches(d.getPath, excludes)) {
                      val s = SerializableFileStatus(d)
                      toProcess.addFirst((d.getPath, p._2 :+ s))
                      processed.add((s, p._2))
                    }
                  case f =>
                    if (
                      (includes.isEmpty || pathMatches(
                        f.getPath,
                        includes
                      )) && !pathMatches(f.getPath, excludes)
                    ) {
                      processed.add((SerializableFileStatus(f), p._2))
                    }
                }
              } catch {
                case e: Exception => exceptions.add(e)
              }
          }
        }
      }
    }

    val pool = Executors.newFixedThreadPool(threads)

    logInfo(s"Beginning recursive list of [$path]")
    val tasks: Seq[Future[Unit]] = List
      .fill(threads)(new FileLister)
      .map(pool.submit)
      .map(j =>
        Future {
          j.get()
          ()
        }(scala.concurrent.ExecutionContext.global)
      )

    import scala.concurrent.ExecutionContext.Implicits.global
    Await.result(Future.sequence(tasks), Duration.Inf)
    pool.shutdown()

    if (!toProcess.isEmpty)
      throw new RuntimeException(
        "Exception listing files, toProcess queue was not empty"
      )

    if (!exceptions.isEmpty) {
      val collectedExceptions = exceptions.iterator().asScala.toList
      collectedExceptions
        .foreach { e =>
          logError("Exception during file listing", e)
        }
      throw collectedExceptions.head
    }

    logInfo(s"Finished recursive list of [$path]")

    processed.iterator().asScala.toSeq // Lazy streamify?

  }

  /** List all files in the given source URIs. This function will throw an exception if any source files collide on
    * identical destination locations and any collisions on any cases where a source files is the same as the
    * destination file (copying between the same FileSystem)
    */
  def getSourceFiles(
      sparkContext: SparkContext,
      sourceURIs: Seq[URI],
      destinationURI: URI,
      updateOverwritePathBehaviour: Boolean,
      numListstatusThreads: Int,
      includes: List[Regex],
      excludes: List[Regex],
      excludeHiddenFile: Boolean
  ): RDD[KeyedCopyDefinition] = {
    val sourceRDD = sourceURIs
      .map { sourceURI =>
        val sourceFS =
          new Path(sourceURI).getFileSystem(sparkContext.hadoopConfiguration)
        sparkContext
          .parallelize(
            FileListUtils.listFiles(
              sourceFS,
              new Path(sourceURI),
              numListstatusThreads,
              !updateOverwritePathBehaviour,
              includes,
              excludes,
              excludeHiddenFile
            )
          )
          .map { case (f, d) =>
            val dependentFolders = d.map { dl =>
              val udl = PathUtils.sourceURIToDestinationURI(
                dl.uri,
                sourceURI,
                destinationURI,
                updateOverwritePathBehaviour
              )
              SingleCopyDefinition(dl, udl)
            }
            val fu = PathUtils.sourceURIToDestinationURI(
              f.uri,
              sourceURI,
              destinationURI,
              updateOverwritePathBehaviour
            )
            CopyDefinitionWithDependencies(f, fu, dependentFolders)
          }
      }
      .reduce(_ union _)
      .map(_.toKeyedDefinition)

    handleSourceCollisions(sourceRDD)

    handleDestCollisions(sourceRDD)

    sourceRDD
  }

  /** List all files at the destination path
    */
  def getDestinationFiles(
      sparkContext: SparkContext,
      destinationPath: Path,
      options: DistCpOption
  ): RDD[(URI, SerializableFileStatus)] = {
    val destinationFS =
      destinationPath.getFileSystem(sparkContext.hadoopConfiguration)
    sparkContext
      .parallelize(
        FileListUtils.listFiles(
          destinationFS,
          destinationPath,
          options.getNumListstatusThreads,
          false,
          List.empty,
          List.empty,
          true
        )
      )
      .map { case (f, _) => (f.getPath.toUri, f) }
  }

  /** Throw an exception if any source files collide on identical destination locations
    */
  def handleSourceCollisions(source: RDD[KeyedCopyDefinition]): Unit = {
    val collisions = source
      .groupByKey()
      .filter(_._2.size > 1)

    collisions
      .foreach { case (f, l) =>
        logError(
          s"The following files will collide on destination file [$f]: ${l.map(_.source.getPath).mkString(", ")}"
        )
      }

    if (!collisions.isEmpty())
      throw new RuntimeException(
        "Collisions found where multiple source files lead to the same destination location; check executor logs for specific collision detail."
      )
  }

  /** Throw an exception for any collisions on any cases where a source files is the same as the destination file
    * (copying between the same FileSystem)
    */
  def handleDestCollisions(source: RDD[KeyedCopyDefinition]): Unit = {

    val collisions = source
      .collect {
        case (_, CopyDefinitionWithDependencies(s, d, _)) if s.uri == d => d
      }

    collisions
      .foreach { d =>
        logError(
          s"The following file has the same source and destination location: [$d]"
        )
      }

    if (!collisions.isEmpty())
      throw new RuntimeException(
        "Collisions found where a file has the same source and destination location; check executor logs for specific collision detail."
      )
  }

}
