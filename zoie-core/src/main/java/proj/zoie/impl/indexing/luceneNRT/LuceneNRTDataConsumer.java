package proj.zoie.impl.indexing.luceneNRT;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import proj.zoie.api.DataConsumer;
import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.ZoieException;
import proj.zoie.api.indexing.ZoieIndexable;
import proj.zoie.api.indexing.ZoieIndexable.IndexingReq;
import proj.zoie.api.indexing.ZoieIndexableInterpreter;

public class LuceneNRTDataConsumer<D> implements DataConsumer<D>, IndexReaderFactory<IndexReader>
{
  private static final Logger logger = Logger.getLogger(LuceneNRTDataConsumer.class);

  /**
   * document ID field name
   */
  public static final String DOCUMENT_ID_FIELD = "id";

  private IndexWriter _writer;
  private Analyzer _analyzer;
  private ZoieIndexableInterpreter<D> _interpreter;
  private Directory _dir;

  public LuceneNRTDataConsumer(File dir, ZoieIndexableInterpreter<D> interpreter) throws IOException
  {
    this(FSDirectory.open(dir), new StandardAnalyzer(Version.LUCENE_34), interpreter);
  }

  public LuceneNRTDataConsumer(File dir, Analyzer analyzer, ZoieIndexableInterpreter<D> interpreter) throws IOException
  {
    this(FSDirectory.open(dir), analyzer, interpreter);
  }

  public LuceneNRTDataConsumer(Directory dir, Analyzer analyzer, ZoieIndexableInterpreter<D> interpreter)
  {
    _writer = null;
    _analyzer = analyzer;
    _interpreter = interpreter;
    _dir = dir;
  }

  public void start()
  {
    try
    {
	  IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_34,_analyzer);
      _writer = new IndexWriter(_dir, config);
    } catch (IOException e)
    {
      logger.error("uanble to start consumer: " + e.getMessage(), e);
    }
  }

  public void shutdown()
  {
    if (_writer != null)
    {
      try
      {
        _writer.close();
      } catch (IOException e)
      {
        logger.error(e.getMessage(), e);
      }
    }
  }

  public void consume(Collection<proj.zoie.api.DataConsumer.DataEvent<D>> events) throws ZoieException
  {
    if (_writer == null)
    {
      throw new ZoieException("Internal IndexWriter null, perhaps not started?");
    }

    if (events.size() > 0)
    {
      String version = null;
      for (DataEvent<D> event : events)
      {
        ZoieIndexable indexable = _interpreter.convertAndInterpret(event.getData());
        version = event.getVersion();
        if (indexable.isSkip())
          continue;

        try
        {
          _writer.deleteDocuments(new Term(DOCUMENT_ID_FIELD, String.valueOf(indexable.getUID())));
        } catch (IOException e)
        {
          throw new ZoieException(e.getMessage(), e);
        }

        IndexingReq[] reqs = indexable.buildIndexingReqs();
        for (IndexingReq req : reqs)
        {
          Analyzer localAnalyzer = req.getAnalyzer();
          Document doc = req.getDocument();
          Field uidField = new Field(DOCUMENT_ID_FIELD, String.valueOf(indexable.getUID()), Store.NO, Index.NOT_ANALYZED_NO_NORMS);
          uidField.setOmitNorms(true);
          doc.add(uidField);
          if (localAnalyzer == null)
            localAnalyzer = _analyzer;
          try
          {
            _writer.addDocument(doc, localAnalyzer);
          } catch (IOException e)
          {
            throw new ZoieException(e.getMessage(), e);
          }
        }
      }
      if (version!=null){
    	  HashMap<String,String> versionData = new HashMap<String,String>();
    	  versionData.put("version",version);
    	  try {
			_writer.commit(versionData);
		  } catch (IOException e) {
			  throw new ZoieException(e.getMessage(), e);
		  }
      }
    }
  }

  public Analyzer getAnalyzer()
  {
    return _analyzer;
  }
  
  private volatile String _currentReaderVersion = null;

  @Override
  public String getCurrentReaderVersion() {
	return _currentReaderVersion;
  }

  public IndexReader getDiskIndexReader() throws IOException
  {
    if (_writer != null)
    {
      IndexReader reader = IndexReader.open(LuceneNRTDataConsumer.this._writer, true);
      Map<String,String> userData = reader.getCommitUserData();
      if (userData!=null){
    	  _currentReaderVersion = userData.get("version");
      }
      return reader;
    } else
    {
      return null;
    }
  }

  public List<IndexReader> getIndexReaders() throws IOException
  {
    IndexReader subReader = getDiskIndexReader();
    ArrayList<IndexReader> list = new ArrayList<IndexReader>();
    if (subReader != null)
    {
      list.add(subReader);
    }
    return list;
  }

  public void returnIndexReaders(List<IndexReader> readers)
  {
    if (readers != null)
    {
      for (IndexReader r : readers)
      {
        try
        {
          r.close();
        } catch (Exception e)
        {
          logger.error(e.getMessage(), e);
        }
      }
    }
  }

  public String getVersion()
  {
    throw new UnsupportedOperationException();
  }

	public Comparator<String> getVersionComparator()
  {
    throw new UnsupportedOperationException();
  }
}
