package top
import chisel3._

import scala.io.Source
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import parameters._

import scala.collection.mutable.ArrayBuffer

object helper{
  def BigInt2ByteArray(n: BigInt, len: Int): Array[Byte] = n.toByteArray.takeRight(len).reverse.padTo(len, 0.toByte)
  def Hex2ByteArray(hex: String, len: Int): Array[Byte] = BigInt("00" ++ hex, 16).toByteArray.takeRight(len).reverse.padTo(len, 0.toByte)
  def ByteArray2BigInt(ba: Array[Byte]) = BigInt(0.toByte +: ba.reverse)
}

class MetaData{
  var kernel_id: BigInt = 0
  var kernel_size: Array[BigInt] = Array(0, 0, 0)
  var wf_size: BigInt = 0
  var wg_size: BigInt = 0
  var metaDataBaseAddr: BigInt = 0
  var ldsSize: BigInt = 0
  var pdsSize: BigInt = 0
  var sgprUsage: BigInt = 0
  var vgprUsage: BigInt = 0
  var pdsBaseAddr: BigInt = 0
  var num_buffer: BigInt = 1
  var buffer_base = new Array[BigInt](0)
  var buffer_size = new Array[BigInt](0)
  var lds_mem_base = new Array[BigInt](0)
  var lds_mem_size = new Array[BigInt](0)

  def generateHostReq(i: BigInt, j: BigInt, k: BigInt) = {
    val blockID = (i * kernel_size(1) + j) * kernel_size(2) + k
    (new host2CTA_data).Lit(
      _.host_wg_id -> ("b" + blockID.toString(2) + "0" * CU_ID_WIDTH).U,
      _.host_num_wf -> wg_size.U,
      _.host_wf_size -> wf_size.U,
      _.host_start_pc -> "h80000000".U,
      _.host_vgpr_size_total -> (wg_size * vgprUsage).U,
      _.host_sgpr_size_total -> (wg_size * sgprUsage).U,
      _.host_lds_size_total -> 128.U, // TODO: fix // ldsSize
      _.host_gds_size_total -> 0.U,
      _.host_vgpr_size_per_wf -> vgprUsage.U,
      _.host_sgpr_size_per_wf -> sgprUsage.U,
      _.host_gds_baseaddr -> 0.U,
      _.host_pds_baseaddr -> (pdsBaseAddr + blockID * pdsSize * wf_size * wg_size).U,
      _.host_csr_knl -> metaDataBaseAddr.U,
      _.host_kernel_size_3d -> Vec(3, UInt(WG_SIZE_X_WIDTH.W)).Lit(0 -> i.U, 1 -> j.U, 2 -> k.U)
    )
  }
}

object MetaData{
  def parseHex(buf: Iterator[String], len: Int = 32): BigInt = { // only support 32b and 64b hex
    val lo = if (buf.hasNext) buf.next() else "0"
    val hi = if (buf.hasNext && len > 32) buf.next() else ""
    BigInt(hi ++ lo, 16)
  }
  def apply(metafile: String) = {
    val buf = {
      val file = Source.fromFile(metafile)
      file.getLines()
    }
    new MetaData{
      kernel_id = parseHex(buf, 64)
      kernel_size = kernel_size.map{ _ => parseHex(buf, 64) }
      wf_size = parseHex(buf, 64)
      wg_size = parseHex(buf, 64)
      metaDataBaseAddr = parseHex(buf, 64)
      ldsSize = parseHex(buf, 64)
      pdsSize = parseHex(buf, 64)
      sgprUsage = parseHex(buf, 64)
      vgprUsage = parseHex(buf, 64)
      pdsBaseAddr = parseHex(buf, 64)
      num_buffer = parseHex(buf, 64)
      for( i <- 0 until num_buffer.toInt){
        val parsed = parseHex(buf, 64)
        if(parsed < BigInt("80000000", 16) && parsed >= BigInt("70000000", 16))
          lds_mem_base = lds_mem_base :+ parsed
        else
          buffer_base = buffer_base :+ parsed
      }
      for (i <- 0 until num_buffer.toInt) {
        val parsed = parseHex(buf, 64)
        if(i < lds_mem_base.length)
          lds_mem_size = lds_mem_size :+ parsed
        else
          buffer_size = buffer_size :+ parsed
      }
    }
  }
}

class DynamicMem(val stepSize: Int = 4096){
  class Page(val startAddr: BigInt) {
    val dat = Array.fill(stepSize)(0.toByte)
  }
  var pages = new scala.collection.mutable.ArrayBuffer[Page](0)
  def insertPage(startAddr: BigInt): Unit = {
    if(pages.isEmpty) {
      pages = pages :+ new Page(startAddr)
      return
    }
    else if(pages.exists(_.startAddr == startAddr)) return

    val i = pages.lastIndexWhere(_.startAddr < startAddr) + 1
    pages.insert(i, new Page(startAddr))
  }

  def readMem(addr: BigInt, len: Int): Array[Byte] = {
    val lower = addr - addr % stepSize
    val upper = (addr + len) - (addr + len) % stepSize
    var res = new Array[Byte](0)
    for(currentPageBase <- lower to upper by stepSize){
      if(!pages.exists(_.startAddr == currentPageBase))
        insertPage(currentPageBase)
      val slice = pages.filter(_.startAddr == currentPageBase)(0).dat.slice(
        if(addr < currentPageBase) 0 else (addr - currentPageBase).toInt,
        if(addr + len > currentPageBase + stepSize) stepSize else (addr + len - currentPageBase).toInt
      )
      res = res ++ slice
    }
    res
  }

  def writeMem(addr: BigInt, len: Int, data: Array[Byte], mask: IndexedSeq[Boolean]): Unit = {
    val lower = addr - addr % stepSize
    val upper = (addr + len) - (addr + len) % stepSize
    for(currentPageBase <- lower to upper by stepSize){
      if (!pages.exists(_.startAddr == currentPageBase))
        insertPage(currentPageBase)
      val idx = pages.lastIndexWhere(_.startAddr == currentPageBase)
      val offset_L = if(addr > currentPageBase) (addr % stepSize).toInt else 0
      val offset_R = if(addr + len < currentPageBase + stepSize) ((addr + len) % stepSize).toInt else stepSize
      for(i <- offset_L until offset_R)
        if(mask(i - offset_L))
          pages(idx).dat(i) = data(i - offset_L)
    }
  }
}

class MemBox(metafile: String, datafile: String){
  import helper._
  val bytesPerLine = 4
  val metaData = MetaData(metafile)
  var memory = { // 32bit per line file -> byte array memory
    val file = Source.fromFile(datafile)
    file.getLines().map(Hex2ByteArray(_, 4)).reduce(_ ++ _)
  }
  val mem_size = metaData.buffer_size
  val mem_base = mem_size.indices.toArray.map(mem_size.slice(0, _).sum)

  val lds_memory = new DynamicMem
  lds_memory.insertPage(BigInt("70000000", 16))

  // move lds data from datafile to dynamic ram
  for (i <- metaData.lds_mem_base.indices) {
    val cut = memory.splitAt(metaData.lds_mem_size(i).toInt)
    lds_memory.writeMem(metaData.lds_mem_base(i), metaData.lds_mem_size(i).toInt,
      cut._1, IndexedSeq.fill(metaData.lds_mem_size(i).toInt)(true))
    memory = cut._2
  }
  /*
  Word Map:
  0x00    0x04030201
  0x04    0x08070605
  ... ...
  Byte Map:
  [7]   [6]   [5]   [4]   [3]   [2]   [1]   [0]   | Index
 0x08  0x07  0x06  0x05  0x04  0x03  0x02  0x01   | Byte Value

  */
  def readMem(addr: BigInt, len: Int): Array[Byte] = {
    if(addr < BigInt("80000000", 16) && addr >= BigInt("70000000", 16))
      return lds_memory.readMem(addr, len)
    val findBuf = (0 until metaData.num_buffer.toInt).filter(i =>
      addr >= metaData.buffer_base(i) && addr < metaData.buffer_base(i) + metaData.buffer_size(i)
    )
    if(findBuf.isEmpty){
      Array.fill(len)(0.toByte)
    }
    else{
      val paddr = mem_base(findBuf.head) + addr - metaData.buffer_base(findBuf.head)
      val paddr_tail = paddr + len
      val buffer_tail = mem_base(findBuf.head) + metaData.buffer_size(findBuf.head)
      val true_tail: BigInt = if(paddr_tail <= buffer_tail) paddr_tail else buffer_tail
      memory.slice(paddr.toInt, true_tail.toInt).padTo(len, 0.toByte)
    }
  }
  def writeMem(addr: BigInt, len: Int, data: Array[Byte], mask: IndexedSeq[Boolean]): Unit = { // 1-bit mask <-> 1 byte data
    if (addr < BigInt("80000000", 16)) {
      lds_memory.writeMem(addr, len, data, mask)
      return
    }
    val findBuf = (0 until metaData.num_buffer.toInt).filter(i =>
      addr >= metaData.buffer_base(i) && addr + len <= metaData.buffer_base(i) + metaData.buffer_size(i)
    )
    if(findBuf.nonEmpty){
      val paddr = mem_base(findBuf.head) + addr - metaData.buffer_base(findBuf.head)
      for (i <- 0 until len){
        if(mask(i))
          memory(paddr.toInt + i) = data(i)
      }
    }
  }
}
