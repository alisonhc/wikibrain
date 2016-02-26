package org.wikibrain.sr.word2vec.universal;

import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.WikiBrainException;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.dao.UniversalPageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LanguageSet;
import org.wikibrain.core.nlp.Dictionary;
import org.wikibrain.download.FileDownloader;
import org.wikibrain.phrases.LinkProbabilityDao;
import org.wikibrain.sr.SRBuilder;
import org.wikibrain.sr.wikify.*;
import org.wikibrain.utils.WpIOUtils;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * @author Shilad Sen
 */
public class UniversalWord2VecMain {
    private static final int OPTIMAL_FILE_SIZE = 50 * 1024 * 1024;

    private final Language lang;
    private final Env env;
    private final TIntIntMap concepts;

    private static final String[][] CORPORA = {
            { "simple", "http://shilad.com/news.2007.en.shuffled.gz"},    // A Smallish file for testing.
            { "cs", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.cs.shuffled.gz"},
            { "de", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.de.shuffled.gz"},
            { "en", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.en.shuffled.gz"},
            { "es", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.es.shuffled.gz"},
            { "fr", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.fr.shuffled.gz"},
            { "hi", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2012.hi.shuffled.gz"},
            { "ru", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.ru.shuffled.gz"},
            { "cs", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.cs.shuffled.gz"},
            { "de", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.de.shuffled.gz"},
            { "en", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.en.shuffled.gz"},
            { "es", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.es.shuffled.gz"},
            { "fr", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.fr.shuffled.gz"},
            { "hi", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.hi.shuffled.gz"},
            { "ru", "http://www.statmt.org/wmt14/training-monolingual-news-crawl/news.2013.ru.shuffled.gz"},
    };

    public UniversalWord2VecMain(Env env, Language lang) throws ConfigurationException, DaoException {
        this.env = env;
        this.lang = lang;
        UniversalPageDao univDao = env.getConfigurator().get(UniversalPageDao.class);
        Map<Language, TIntIntMap> allConcepts = univDao.getAllLocalToUnivIdsMap(new LanguageSet(lang));
        this.concepts = allConcepts.containsKey(lang) ? allConcepts.get(lang) : new TIntIntHashMap();
    }

    public void create(String path) throws ConfigurationException, DaoException, WikiBrainException, IOException, InterruptedException {
        SRBuilder builder = new SRBuilder(env, "word2vec", lang);
        builder.setSkipBuiltMetrics(true);
        builder.setCreateFakeGoldStandard(true);
        builder.build();

        FileUtils.deleteQuietly(new File(path));
        FileUtils.forceMkdir(new File(path));
        Corpus c = env.getConfigurator().get(Corpus.class, "wikified", "language", lang.getLangCode());
        if (c == null) throw new IllegalStateException("Couldn't find wikified corpus for language " + lang);
        if (!c.exists()) {
            c.create();
        }

        RotatingWriter writer = new RotatingWriter(
                path + "/corpus." + lang.getLangCode() + ".",
                ".txt",
                OPTIMAL_FILE_SIZE);

        LocalPageDao pageDao = env.getConfigurator().get(LocalPageDao.class);
        Wikifier wikifier = env.getConfigurator().get(Wikifier.class, "websail-final", "language", lang.getLangCode());
        LinkProbabilityDao linkDao = env.getConfigurator().get(LinkProbabilityDao.class);

        // Process the wikipedia corpus
        WbCorpusLineReader cr = new WbCorpusLineReader(c.getCorpusFile());
        for (WbCorpusLineReader.Line line : cr) {
            processLine(writer, line.getLine());
        }

        // Process the online corpora
        File tmp = WpIOUtils.createTempDirectory(lang.getLangCode() + "corpora");
        for (String [] info : CORPORA) {
            if (info[0].equals(lang.getLangCode())) {
                URL url = new URL(info[1]);
                String name = new File(url.getFile()).getName();
                FileDownloader downloader = new FileDownloader();
                File in = downloader.download(url, new File(tmp, name));
                in.deleteOnExit();
                File out = new File(in.toString().replace(".gz", "") + ".wikified");
                PlainTextCorpusCreator ptc = new PlainTextCorpusCreator(lang, wikifier, pageDao, linkDao, in);
                ptc.write(out);

                WbCorpusLineReader r = new WbCorpusLineReader(new File(out, "corpus.txt"));
                for (WbCorpusLineReader.Line line : r) {
                    processLine(writer, line.getLine());
                }
            }
        }
        FileUtils.deleteQuietly(tmp);

        writer.close();
    }

    private void processLine(RotatingWriter writer, String line) throws IOException {
        List<String> words = new ArrayList<String>();
        for (String word : line.split(" +")) {
            int mentionStart = word.indexOf(":/w/");
            if (mentionStart >= 0) {
                Matcher m = Dictionary.PATTERN_MENTION.matcher(word.substring(mentionStart));
                if (m.matches()) {
                    List<String> parts = new ArrayList<String>();
                    parts.add(makeWordToken(word.substring(0, mentionStart)));
                    int wpId2 = Integer.valueOf(m.group(3));
                    if (wpId2 >= 0) {
                        parts.add(word.substring(mentionStart + 1));
                        if (concepts.containsKey(wpId2)) {
                            parts.add("/c/" + concepts.get(wpId2));
                        }
                        Collections.shuffle(parts);
                    }
                    words.addAll(parts);
                } else {
                    words.add(makeWordToken(word));
                }
            } else {
                words.add(makeWordToken(word));
            }
        }
        writer.write(StringUtils.join(words, " ") + "\n");
    }

    private String makeWordToken(String word) {
        return lang.getLangCode() + ":" + word;
    }

    static class RotatingWriter implements Closeable {
        private final String prefix;
        private final String suffix;
        private final int maxBytes;
        private int fileNum = 0;
        private int numBytes = 0;
        private BufferedWriter writer = null;

        RotatingWriter(String prefix, String suffix, int maxBytes) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.maxBytes = maxBytes;
        }

        void write(String text) throws IOException {
            possiblyRotateWriter();
            numBytes += text.getBytes("UTF-8").length;
            writer.write(text);
        }

        private void possiblyRotateWriter() throws IOException {
            if (writer == null || numBytes >= maxBytes) {
                if (writer != null) {
                    close();
                    fileNum++;
                    numBytes = 0;
                }
                writer = WpIOUtils.openWriter(String.format("%s%05d%s", prefix, fileNum, suffix));
            }
        }

        @Override
        public void close() throws IOException {
            if (writer != null) {
                IOUtils.closeQuietly(writer);
                writer = null;
            }
        }
    }

    public static void main(String args[]) throws ConfigurationException, DaoException, IOException, WikiBrainException, InterruptedException {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .hasArg()
                        .isRequired()
                        .withLongOpt("output")
                        .withDescription("corpus output directory (existing data will be lost)")
                        .create("o"));

        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println( "Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("UniversalWord2VecMain", options);
            return;
        }
        Env env = new EnvBuilder(cmd).build();
        for (Language l : env.getLanguages()) {
            UniversalWord2VecMain creator = new UniversalWord2VecMain(env, l);
            creator.create(cmd.getOptionValue("o") + "/" + l.getLangCode());
        }
    }
}
