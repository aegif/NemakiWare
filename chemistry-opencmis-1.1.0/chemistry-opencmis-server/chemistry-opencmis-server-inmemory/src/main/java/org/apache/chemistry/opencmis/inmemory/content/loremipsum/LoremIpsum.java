/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.inmemory.content.loremipsum;

import java.io.IOException;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A generator of lorem ipsum text ported from the Python implementation at
 * http://code.google.com/p/lorem-ipsum-generator/.
 * 
 * Note: Original code licensed under the BSD license
 */
public class LoremIpsum {

    private static class WordLengthPair {
        public int len1;
        public int len2;

        public WordLengthPair(int len1, int len2) {
            this.len1 = len1;
            this.len2 = len2;
        }

        @Override
        public String toString() {
            return "WordLengthPair: len1: " + len1 + ", len2: " + len2;
        }

        @Override
        public boolean equals(Object other) {
            if (this == null || other == null) {
                return false;
            }
            if (other.getClass() != getClass()) {
                return false;
            }

            return len1 == ((WordLengthPair) other).len1 && len2 == ((WordLengthPair) other).len2;
        }

        @Override
        public int hashCode() {
            return len1 ^ len2;
        }
    }

    /**
     * Delimiters that end sentences.
     * 
     * @type {Array.<string>}
     */
    private static final String DELIMITERS_SENTENCES[] = { ".", "?", "!" };

    /**
     * Regular expression for splitting a text into sentences.
     * 
     * @type {RegExp}
     */
    private static final String SENTENCE_SPLIT_REGEX = "[\\.\\?\\!]";

    /**
     * Delimiters that end words.
     * 
     * @type {Array.<string>}
     */
    private static final String DELIMITERS_WORDS[] = { ".", ",", "?", "!" };

    /**
     * Regular expression for splitting text into words.
     */
    private static final String WORD_SPLIT_REGEX = "\\s";

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    /**
     * Words that can be used in the generated output. Maps a word-length to a
     * list of words of that length.
     */
    private Map<Integer, List<String>> words;

    /**
     * Chains of three words that appear in the sample text Maps a pair of
     * word-lengths to a third word-length and an optional piece of trailing
     * punctuation (for example, a period, comma, etc.).
     */
    private Map<WordLengthPair, List<WordInfo>> chains;

    /**
     * Pairs of word-lengths that can appear at the beginning of sentences.
     */
    private List<WordLengthPair> starts;

    /**
     * Average sentence length in words.
     */

    private double sentenceMean;

    /**
     * Sigma (sqrt of Objectiance) for the sentence length in words.
     */
    private double sentenceSigma;

    /**
     * Average paragraph length in sentences.
     */
    private double paragraphMean;

    /**
     * Sigma (sqrt of variance) for the paragraph length in sentences.
     * 
     * @type {number}
     */
    private double paragraphSigma;

    /**
     * Sample that the generated text is based on .
     * 
     * @type {string}
     */
    private String sample = SAMPLE;

    /**
     * Dictionary of words.
     * 
     * @type {string}
     */
    private String dictionary = DICT;

    /**
     * Picks a random element of the array.
     * 
     * @param array
     *            the array to pick from
     * @return an element from the array
     */
    private WordInfo randomChoice(WordInfo[] array) {
        return array[randomInt(array.length)];
    }

    private String randomChoice(String[] array) {
        return array[randomInt(array.length)];
    }

    private int randomInt(int length) {
        return randomGenerator.nextInt(length);
    }

    private static class WordInfo {
        int len;
        String delim;
    }

    private SecureRandom randomGenerator = new SecureRandom();

    /**
     * Generates random strings of "lorem ipsum" text, based on the word
     * distribution of a sample text, using the words in a dictionary.
     */
    public LoremIpsum() {
        generateChains(this.sample);
        generateStatistics(this.sample);
        initializeDictionary(this.dictionary);
    }

    public LoremIpsum(String sample, String dictionary) {
        this.sample = sample;
        this.dictionary = dictionary;
        generateChains(this.sample);
        generateStatistics(this.sample);
        initializeDictionary(this.dictionary);
    }

    public LoremIpsum(String sample, String[] newDictionary) {
        this.sample = sample;
        this.dictionary = null;
        generateChains(this.sample);
        generateStatistics(this.sample);
        initializeDictionary(newDictionary);
    }

    public LoremIpsum(String sample) {
        this.sample = sample;
        String[] dictWords = filterNotEmptyOrWhiteSpace(sample.split("[^\\p{L}]"/* "\\W" */)).toArray(new String[0]);
        Set<String> dict = new HashSet<String>(Arrays.asList(dictWords));
        dictWords = dict.toArray(new String[0]);
        Arrays.sort(dictWords);

        generateChains(this.sample);
        generateStatistics(this.sample);
        initializeDictionary(dictWords);
    }

    /**
     * Generates a single lorem ipsum paragraph, of random length.
     * 
     * @param optStartWithLorem
     *            Whether to start the sentence with the standard
     *            "Lorem ipsum..." first sentence
     * @return the generated sentence
     */
    public String generateParagraph(boolean optStartWithLorem) {
        // The length of the paragraph is a normally distributed random
        // Objectiable.
        Double paragraphLengthDbl = randomNormal(this.paragraphMean, this.paragraphSigma);
        int paragraphLength = Math.max((int) Math.floor(paragraphLengthDbl), 1);

        // Construct a paragraph from a number of sentences.
        List<String> paragraph = new ArrayList<String>();
        boolean startWithLorem = optStartWithLorem;
        while (paragraph.size() < paragraphLength) {
            String sentence = this.generateSentence(startWithLorem);
            paragraph.add(sentence);
            startWithLorem = false;
        }

        StringBuffer result = new StringBuffer();
        // Form the paragraph into a string.
        for (String sentence : paragraph) {
            result.append(sentence);
            result.append(" ");
        }
        return result.toString();
    }

    /**
     * Generates a single sentence, of random length.
     * 
     * @param optStartWithLorem
     *            Whether to start the sentence with the standard
     *            "Lorem ipsum..." first sentence.
     * @return the generated sentence
     */
    public String generateSentence(boolean optStartWithLorem) {
        if (this.chains.size() == 0 || this.starts.size() == 0) {
            throw new RuntimeException("No chains created (Invalid sample text?)");
        }

        if (this.words.size() == 0) {
            throw new RuntimeException("No dictionary");
        }

        // The length of the sentence is a normally distributed random
        // Objectiable.
        double sentenceLengthDbl = randomNormal(this.sentenceMean, this.sentenceSigma);
        int sentenceLength = Math.max((int) Math.floor(sentenceLengthDbl), 1);

        String wordDelimiter = ""; // Defined here in case while loop doesn't
                                   // run

        // Start the sentence with "Lorem ipsum...", if desired
        List<String> sentence;

        if (optStartWithLorem) {
            String lorem = "lorem ipsum dolor sit amet, consecteteur adipiscing elit";
            sentence = new ArrayList<String>(Arrays.asList(splitWords(lorem)));
            if (sentence.size() > sentenceLength) {
                sentence.subList(0, sentenceLength);
            }
            String lastWord = sentence.get(sentence.size() - 1);
            String lastChar = lastWord.substring(lastWord.length() - 1);
            if (contains(DELIMITERS_WORDS, lastChar)) {
                wordDelimiter = lastChar;
            }
        } else {
            sentence = new ArrayList<String>();
        }

        WordLengthPair previous = new WordLengthPair(0, 0);

        // Generate a sentence from the "chains"
        while (sentence.size() < sentenceLength) {
            // If the current starting point is invalid, choose another randomly
            if (!this.chains.containsKey(previous)) {
                previous = this.chooseRandomStart_();
            }

            // Choose the next "chain" to go to. This determines the next word
            // length we use, and whether there is e.g. a comma at the end of
            // the word.
            WordInfo chain = randomChoice(this.chains.get(previous).toArray(new WordInfo[0]));
            int wordLength = chain.len;

            // If the word delimiter contained in the chain is also a sentence
            // delimiter, then we don"t include it because we don"t want the
            // sentence to end prematurely (we want the length to match the
            // sentence_length value).
            if (contains(DELIMITERS_SENTENCES, chain.delim)) {
                wordDelimiter = "";
            } else {
                wordDelimiter = chain.delim;
            }

            // Choose a word randomly that matches (or closely matches) the
            // length we're after.
            int closestLength = chooseClosest(this.words.keySet().toArray(new Integer[0]), wordLength);
            String word = randomChoice(this.words.get(closestLength).toArray(new String[0]));

            sentence.add(word + wordDelimiter);
            previous = new WordLengthPair(previous.len2, wordLength);

        }

        // Finish the sentence off with capitalisation, a period and
        // form it into a string
        StringBuffer result = new StringBuffer();
        for (String s : sentence) {
            result.append(s);
            result.append(" ");
        }
        result.deleteCharAt(result.length() - 1);

        result.replace(0, 1, result.substring(0, 1).toUpperCase());
        int strLen = result.length() - 1;
        if (wordDelimiter.length() > 0 && wordDelimiter.charAt(0) == result.charAt(strLen)) {
            result.deleteCharAt(strLen);
        }
        result.append(".");
        return result.toString();
    }

    public String getSample() {
        return sample;
    }

    public void setSample(String sample) {
        this.sample = sample;
        generateChains(this.sample);
        generateStatistics(this.sample);
    }

    public String getDictionary() {
        return dictionary;
    }

    public void setDictionary(String dictionary) {
        this.dictionary = dictionary;
        initializeDictionary(this.dictionary);
    }

    /**
     * Generates multiple paragraphs of text, with begin before the paragraphs,
     * end after the paragraphs, and between between each two paragraphs.
     */
    private String generateMarkupParagraphs(String begin, String end, String between, int quantity,
            boolean startWithLorem) {

        StringBuffer text = new StringBuffer();

        text.append(begin);
        String para = generateParagraph(startWithLorem);
        text.append(para);
        while (text.length() < quantity) {
            para = generateParagraph(false);
            text.append(para);
            if (text.length() < quantity) {
                text.append(between);
            }
        }

        text.append(end);
        return text.toString();
    }

    /**
     * Generates multiple paragraphs of text, with begin before the paragraphs,
     * end after the paragraphs, and between between each two paragraphs.
     * 
     * @throws IOException
     */
    private void generateMarkupParagraphs(Appendable writer, String begin, String end, String between, int quantity,
            boolean startWithLorem) throws IOException {

        int len = begin.length();
        writer.append(begin);
        String para = generateParagraph(startWithLorem);
        len += para.length();
        writer.append(para);
        while (len < quantity) {
            para = generateParagraph(false);
            len += para.length();
            writer.append(para);
            if (len < quantity) {
                writer.append(between);
                len += para.length();
            }
        }

        writer.append(end);
    }

    /**
     * Generates multiple sentences of text, with begin before the sentences,
     * end after the sentences, and between between each two sentences.
     */
    private String generateMarkupSentences(String begin, String end, String between, int quantity,
            boolean startWithLorem) {

        StringBuffer text = new StringBuffer();
        text.append(begin);
        String sentence = generateSentence(startWithLorem);
        text.append(sentence);

        while (text.length() < quantity) {
            sentence = generateSentence(false);
            text.append(sentence);
            if (text.length() < quantity) {
                text.append(between);
            }
        }

        text.append(end);
        return text.toString();
    }

    /**
     * Generates the chains and starts values required for sentence generation.
     * 
     * @param sample
     *            The same text.
     */
    private void generateChains(String sample) {

        String[] words = splitWords(sample);
        WordInfo[] wordInfos = generateWordInfo(words);
        WordLengthPair previous = new WordLengthPair(0, 0);
        List<WordLengthPair> starts = new ArrayList<WordLengthPair>();
        List<String> delimList = Arrays.asList(DELIMITERS_SENTENCES);
        Map<WordLengthPair, List<WordInfo>> chains = new HashMap<WordLengthPair, List<WordInfo>>();

        for (WordInfo wi : wordInfos) {
            if (wi.len == 0) {
                continue;
            }

            List<WordInfo> value = chains.get(previous);
            if (null == value) {
                chains.put(previous, new ArrayList<WordInfo>());
            } else {
                chains.get(previous).add(wi);
            }

            if (delimList.contains(wi.delim)) {
                starts.add(previous);
            }

            previous.len1 = previous.len2;
            previous.len2 = wi.len;
        }

        if (chains.isEmpty()) {
            throw new RuntimeException("Invalid sample text.");
        }

        this.chains = chains;
        this.starts = starts;
    }

    /**
     * Calculates the mean and standard deviation of sentence and paragraph
     * lengths.
     * 
     * @param sample
     *            The same text.
     */
    private void generateStatistics(String sample) {
        this.generateSentenceStatistics(sample);
        this.generateParagraphStatistics(sample);
    }

    /**
     * Calculates the mean and standard deviation of the lengths of sentences
     * (in words) in a sample text.
     * 
     * @param sample
     *            The same text.
     */
    private void generateSentenceStatistics(String sample) {
        List<String> sentences = filterNotEmptyOrWhiteSpace(splitSentences(sample));
        int sentenceLengths[] = new int[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            String[] words = splitWords(sentences.get(i));
            sentenceLengths[i] = words.length;
        }
        this.sentenceMean = mean(sentenceLengths);
        this.sentenceSigma = sigma(sentenceLengths);
    }

    /**
     * Calculates the mean and standard deviation of the lengths of paragraphs
     * (in sentences) in a sample text.
     * 
     * @param sample
     *            The same text.
     */
    private void generateParagraphStatistics(String sample) {
        List<String> paragraphs = filterNotEmptyOrWhiteSpace(splitParagraphs(sample));

        int paragraphLengths[] = new int[paragraphs.size()];
        for (int i = 0; i < paragraphs.size(); i++) {
            String[] sentences = splitSentences(paragraphs.get(i));
            paragraphLengths[i] = sentences.length;
        }

        this.paragraphMean = mean(paragraphLengths);
        this.paragraphSigma = sigma(paragraphLengths);
    }

    /**
     * Sets the generator to use a given selection of words for generating
     * sentences with.
     * 
     * @param dictionary
     *            The dictionary to use.
     */
    private void initializeDictionary(String dictionary) {
        String[] dictionaryWords = splitWords(dictionary);
        initializeDictionary(dictionaryWords);
    }

    private void initializeDictionary(String[] dictionaryWords) {
        words = new HashMap<Integer, List<String>>();
        for (String word : dictionaryWords) {
            List<String> wordWithLen = words.get(word.length());
            if (null == wordWithLen) {
                List<String> list = new ArrayList<String>();
                list.add(word);
                words.put(word.length(), list);
            } else {
                wordWithLen.add(word);
            }
        }

        if (words.size() == 0) {
            throw new RuntimeException("Invalid dictionary.");
        }
    }

    /**
     * Picks a random starting chain.
     * 
     * @return {string} The starting key.
     */
    private WordLengthPair chooseRandomStart_() {
        Set<WordLengthPair> keys = chains.keySet();
        Set<WordLengthPair> validStarts = new HashSet<WordLengthPair>(starts);
        validStarts.retainAll(keys);
        int index = randomInt(validStarts.size());
        WordLengthPair wlPair = validStarts.toArray(new WordLengthPair[0])[index];
        return wlPair;
    }

    /**
     * Splits a piece of text into paragraphs.
     * 
     * @param text
     *            The text to split.
     * @return An array of paragraphs.
     */

    static String[] splitParagraphs(String text) {
        return filterNotEmptyOrWhiteSpace(text.split("\n")).toArray(new String[0]);
    }

    /**
     * Splits a piece of text into sentences.
     * 
     * @param text
     *            The text to split.
     * @return An array of sentences.
     */
    static String[] splitSentences(String text) {
        return filterNotEmptyOrWhiteSpace(text.split(SENTENCE_SPLIT_REGEX)).toArray(new String[0]);
    }

    /**
     * Splits a piece of text into words..
     * 
     * @param text
     *            The text to split.
     * @return An array of words.
     */
    static String[] splitWords(String text) {
        return filterNotEmptyOrWhiteSpace(text.split(WORD_SPLIT_REGEX)).toArray(new String[0]);
    }

    /**
     * Find the number in the list of values that is closest to the target.
     * 
     * @param values
     *            The values.
     * @param target
     *            The target value.
     * @return The closest value.
     */
    static int chooseClosest(Integer[] values, int target) {
        int closest = values[0];
        for (int value : values) {
            if (Math.abs(target - value) < Math.abs(target - closest)) {
                closest = value;
            }
        }

        return closest;
    }

    /**
     * Gets info about a word used as part of the lorem ipsum algorithm.
     * 
     * @param word
     *            The word to check.
     * @return A two element array. The first element is the size of the word.
     *         The second element is the delimiter used in the word.
     */
    private static WordInfo getWordInfo(String word) {
        WordInfo ret = new WordInfo();
        for (String delim : DELIMITERS_WORDS) {
            if (word.endsWith(delim)) {
                ret.len = word.length() - delim.length();
                ret.delim = delim;
                return ret;
            }
        }
        ret.len = word.length();
        ret.delim = "";
        return ret;
    }

    private static WordInfo[] generateWordInfo(String[] words) {
        WordInfo[] result = new WordInfo[words.length];
        int i = 0;
        for (String word : words) {
            result[i++] = getWordInfo(word);
        }
        return result;
    }

    /**
     * Constant used for {@link #randomNormal_}.
     */
    private static final double NV_MAGICCONST_ = 4 * Math.exp(-0.5) / Math.sqrt(2.0);

    /**
     * Generates a random number for a normal distribution with the specified
     * mean and sigma.
     * 
     * @param mu
     *            The mean of the distribution.
     * @param sigma
     *            The sigma of the distribution.
     */
    private static double randomNormal(double mu, double sigma) {
        SecureRandom rnd = new SecureRandom();

        double z = 0.0d;
        while (true) {
            double u1 = rnd.nextDouble();
            double u2 = 1.0d - rnd.nextDouble();
            z = NV_MAGICCONST_ * (u1 - 0.5d) / u2;
            double zz = z * z / 4.0d;
            if (zz <= -Math.log(u2)) {
                break;
            }
        }
        return mu + z * sigma;
    }

    /**
     * Returns the text if it is not empty or just whitespace.
     * 
     * @param text
     *            the text to check.
     * @return Whether the text is neither empty nor whitespace.
     */
    private static List<String> filterNotEmptyOrWhiteSpace(String[] arr) {
        List<String> result = new ArrayList<String>();
        for (String s : arr) {
            String trims = s.trim();
            if (trims.length() > 0) {
                result.add(trims);
            }
        }
        return result;
    }

    public static double mean(int[] values) {
        return ((double) sum(values)) / ((double) (Math.max(values.length, 1)));
    }

    public static double mean(double[] values) {
        return sum(values) / ((Math.max(values.length, 1)));
    }

    public static double variance(double[] values) {
        double[] squared = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            squared[i] = values[i] * values[i];
        }

        double meanVal = mean(values);
        return mean(squared) - (meanVal * meanVal);
    }

    public static double sigma(int[] values) {
        double[] d = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            d[i] = values[i];
        }

        return sigma(d);
    }

    public static double sigma(double[] values) {
        return Math.sqrt(variance(values));
    }

    public static int sum(int[] values) {
        int sum = 0;
        for (int val : values) {
            sum += val;
        }
        return sum;
    }

    public static double sum(double[] values) {
        double sum = 0.0d;
        for (double val : values) {
            sum += val;
        }
        return sum;
    }

    public static boolean contains(String[] array, String val) {
        for (String s : array) {
            if (s.equals(val)) {
                return true;
            }
        }
        return false;
    }

    /* for unit testing */
    double getSentenceMean() {
        return sentenceMean;
    }

    double getSentenceSigma() {
        return sentenceSigma;
    }

    double getParagraphMean() {
        return paragraphMean;
    }

    double getParagraphSigma() {
        return paragraphSigma;
    }

    /**
     * Dictionary of words for lorem ipsum.
     */
    private static final String DICT = "a ac accumsan ad adipiscing aenean aliquam aliquet amet ante "
            + "aptent arcu at auctor augue bibendum blandit class commodo "
            + "condimentum congue consectetuer consequat conubia convallis cras "
            + "cubilia cum curabitur curae cursus dapibus diam dictum dictumst "
            + "dignissim dis dolor donec dui duis egestas eget eleifend elementum "
            + "elit eni enim erat eros est et etiam eu euismod facilisi facilisis "
            + "fames faucibus felis fermentum feugiat fringilla fusce gravida "
            + "habitant habitasse hac hendrerit hymenaeos iaculis id imperdiet "
            + "in inceptos integer interdum ipsum justo lacinia lacus laoreet "
            + "lectus leo libero ligula litora lobortis lorem luctus maecenas "
            + "magna magnis malesuada massa mattis mauris metus mi molestie "
            + "mollis montes morbi mus nam nascetur natoque nec neque netus "
            + "nibh nisi nisl non nonummy nostra nulla nullam nunc odio orci "
            + "ornare parturient pede pellentesque penatibus per pharetra "
            + "phasellus placerat platea porta porttitor posuere potenti praesent "
            + "pretium primis proin pulvinar purus quam quis quisque rhoncus "
            + "ridiculus risus rutrum sagittis sapien scelerisque sed sem semper "
            + "senectus sit sociis sociosqu sodales sollicitudin suscipit "
            + "suspendisse taciti tellus tempor tempus tincidunt torquent tortor "
            + "tristique turpis ullamcorper ultrices ultricies urna ut Objectius ve "
            + "vehicula vel velit venenatis vestibulum vitae vivamus viverra " + "volutpat vulputate";

    /**
     * A sample to use for generating the distribution of word and sentence
     * lengths in lorem ipsum.
     */
    private static final String SAMPLE = "Lorem ipsum dolor sit amet, consectetuer adipiscing elit. Aenean "
            + "commodo ligula eget dolor. Aenean massa. Cum sociis natoque penatibus "
            + "et magnis dis parturient montes, nascetur ridiculus mus. Donec quam "
            + "felis, ultricies nec, pellentesque eu, pretium quis, sem. Nulla "
            + "consequat massa quis enim. Donec pede justo, fringilla vel, aliquet "
            + "nec, vulputate eget, arcu. In enim justo, rhoncus ut, imperdiet a, "
            + "venenatis vitae, justo. Nullam dictum felis eu pede mollis pretium. "
            + "Integer tincidunt. Cras dapibus. Vivamus elementum semper nisi. Aenean "
            + "vulputate eleifend tellus. Aenean leo ligula, porttitor eu, consequat "
            + "vitae, eleifend ac, enim. Aliquam lorem ante, dapibus in, viverra "
            + "quis, feugiat a, tellus. Phasellus viverra nulla ut metus Objectius "
            + "laoreet. Quisque rutrum. Aenean imperdiet. Etiam ultricies nisi vel "
            + "augue. Curabitur ullamcorper ultricies nisi. Nam eget dui.\n\n" +

            "Etiam rhoncus. Maecenas tempus, tellus eget condimentum rhoncus, sem "
            + "quam semper libero, sit amet adipiscing sem neque sed ipsum. Nam quam "
            + "nunc, blandit vel, luctus pulvinar, hendrerit id, lorem. Maecenas nec "
            + "odio et ante tincidunt tempus. Donec vitae sapien ut libero venenatis "
            + "faucibus. Nullam quis ante. Etiam sit amet orci eget eros faucibus "
            + "tincidunt. Duis leo. Sed fringilla mauris sit amet nibh. Donec sodales "
            + "sagittis magna. Sed consequat, leo eget bibendum sodales, augue velit "
            + "cursus nunc, quis gravida magna mi a libero. Fusce vulputate eleifend "
            + "sapien. Vestibulum purus quam, scelerisque ut, mollis sed, nonummy id, "
            + "metus. Nullam accumsan lorem in dui. Cras ultricies mi eu turpis "
            + "hendrerit fringilla. Vestibulum ante ipsum primis in faucibus orci "
            + "luctus et ultrices posuere cubilia Curae; In ac dui quis mi " + "consectetuer lacinia.\n\n" +

            "Nam pretium turpis et arcu. Duis arcu tortor, suscipit eget, imperdiet "
            + "nec, imperdiet iaculis, ipsum. Sed aliquam ultrices mauris. Integer "
            + "ante arcu, accumsan a, consectetuer eget, posuere ut, mauris. Praesent "
            + "adipiscing. Phasellus ullamcorper ipsum rutrum nunc. Nunc nonummy "
            + "metus. Vestibulum volutpat pretium libero. Cras id dui. Aenean ut eros "
            + "et nisl sagittis vestibulum. Nullam nulla eros, ultricies sit amet, "
            + "nonummy id, imperdiet feugiat, pede. Sed lectus. Donec mollis hendrerit "
            + "risus. Phasellus nec sem in justo pellentesque facilisis. Etiam "
            + "imperdiet imperdiet orci. Nunc nec neque. Phasellus leo dolor, tempus "
            + "non, auctor et, hendrerit quis, nisi.\n\n" +

            "Curabitur ligula sapien, tincidunt non, euismod vitae, posuere "
            + "imperdiet, leo. Maecenas malesuada. Praesent congue erat at massa. Sed "
            + "cursus turpis vitae tortor. Donec posuere vulputate arcu. Phasellus "
            + "accumsan cursus velit. Vestibulum ante ipsum primis in faucibus orci "
            + "luctus et ultrices posuere cubilia Curae; Sed aliquam, nisi quis "
            + "porttitor congue, elit erat euismod orci, ac placerat dolor lectus quis "
            + "orci. Phasellus consectetuer vestibulum elit. Aenean tellus metus, "
            + "bibendum sed, posuere ac, mattis non, nunc. Vestibulum fringilla pede "
            + "sit amet augue. In turpis. Pellentesque posuere. Praesent turpis.\n\n" +

            "Aenean posuere, tortor sed cursus feugiat, nunc augue blandit nunc, eu "
            + "sollicitudin urna dolor sagittis lacus. Donec elit libero, sodales "
            + "nec, volutpat a, suscipit non, turpis. Nullam sagittis. Suspendisse "
            + "pulvinar, augue ac venenatis condimentum, sem libero volutpat nibh, "
            + "nec pellentesque velit pede quis nunc. Vestibulum ante ipsum primis in "
            + "faucibus orci luctus et ultrices posuere cubilia Curae; Fusce id "
            + "purus. Ut Objectius tincidunt libero. Phasellus dolor. Maecenas vestibulum "
            + "mollis diam. Pellentesque ut neque. Pellentesque habitant morbi "
            + "tristique senectus et netus et malesuada fames ac turpis egestas.\n\n" +

            "In dui magna, posuere eget, vestibulum et, tempor auctor, justo. In ac "
            + "felis quis tortor malesuada pretium. Pellentesque auctor neque nec "
            + "urna. Proin sapien ipsum, porta a, auctor quis, euismod ut, mi. Aenean "
            + "viverra rhoncus pede. Pellentesque habitant morbi tristique senectus et "
            + "netus et malesuada fames ac turpis egestas. Ut non enim eleifend felis "
            + "pretium feugiat. Vivamus quis mi. Phasellus a est. Phasellus magna.\n\n" +

            "In hac habitasse platea dictumst. Curabitur at lacus ac velit ornare "
            + "lobortis. Curabitur a felis in nunc fringilla tristique. Morbi mattis "
            + "ullamcorper velit. Phasellus gravida semper nisi. Nullam vel sem. "
            + "Pellentesque libero tortor, tincidunt et, tincidunt eget, semper nec, "
            + "quam. Sed hendrerit. Morbi ac felis. Nunc egestas, augue at "
            + "pellentesque laoreet, felis eros vehicula leo, at malesuada velit leo "
            + "quis pede. Donec interdum, metus et hendrerit aliquet, dolor diam "
            + "sagittis ligula, eget egestas libero turpis vel mi. Nunc nulla. Fusce "
            + "risus nisl, viverra et, tempor et, pretium in, sapien. Donec venenatis " + "vulputate lorem.\n\n" +

            "Morbi nec metus. Phasellus blandit leo ut odio. Maecenas ullamcorper, "
            + "dui et placerat feugiat, eros pede Objectius nisi, condimentum viverra "
            + "felis nunc et lorem. Sed magna purus, fermentum eu, tincidunt eu, "
            + "Objectius ut, felis. In auctor lobortis lacus. Quisque libero metus, "
            + "condimentum nec, tempor a, commodo mollis, magna. Vestibulum "
            + "ullamcorper mauris at ligula. Fusce fermentum. Nullam cursus lacinia "
            + "erat. Praesent blandit laoreet nibh.\n\n" +

            "Fusce convallis metus id felis luctus adipiscing. Pellentesque egestas, "
            + "neque sit amet convallis pulvinar, justo nulla eleifend augue, ac "
            + "auctor orci leo non est. Quisque id mi. Ut tincidunt tincidunt erat. "
            + "Etiam feugiat lorem non metus. Vestibulum dapibus nunc ac augue. "
            + "Curabitur vestibulum aliquam leo. Praesent egestas neque eu enim. In "
            + "hac habitasse platea dictumst. Fusce a quam. Etiam ut purus mattis "
            + "mauris sodales aliquam. Curabitur nisi. Quisque malesuada placerat "
            + "nisl. Nam ipsum risus, rutrum vitae, vestibulum eu, molestie vel, " + "lacus.\n\n" +

            "Sed augue ipsum, egestas nec, vestibulum et, malesuada adipiscing, "
            + "dui. Vestibulum facilisis, purus nec pulvinar iaculis, ligula mi "
            + "congue nunc, vitae euismod ligula urna in dolor. Mauris sollicitudin "
            + "fermentum libero. Praesent nonummy mi in odio. Nunc interdum lacus sit "
            + "amet orci. Vestibulum rutrum, mi nec elementum vehicula, eros quam "
            + "gravida nisl, id fringilla neque ante vel mi. Morbi mollis tellus ac "
            + "sapien. Phasellus volutpat, metus eget egestas mollis, lacus lacus "
            + "blandit dui, id egestas quam mauris ut lacus. Fusce vel dui. Sed in "
            + "libero ut nibh placerat accumsan. Proin faucibus arcu quis ante. In "
            + "consectetuer turpis ut velit. Nulla sit amet est. Praesent metus "
            + "tellus, elementum eu, semper a, adipiscing nec, purus. Cras risus "
            + "ipsum, faucibus ut, ullamcorper id, Objectius ac, leo. Suspendisse "
            + "feugiat. Suspendisse enim turpis, dictum sed, iaculis a, condimentum "
            + "nec, nisi. Praesent nec nisl a purus blandit viverra. Praesent ac "
            + "massa at ligula laoreet iaculis. Nulla neque dolor, sagittis eget, "
            + "iaculis quis, molestie non, velit.\n\n" +

            "Mauris turpis nunc, blandit et, volutpat molestie, porta ut, ligula. "
            + "Fusce pharetra convallis urna. Quisque ut nisi. Donec mi odio, faucibus "
            + "at, scelerisque quis, convallis in, nisi. Suspendisse non nisl sit amet "
            + "velit hendrerit rutrum. Ut leo. Ut a nisl id ante tempus hendrerit. "
            + "Proin pretium, leo ac pellentesque mollis, felis nunc ultrices eros, "
            + "sed gravida augue augue mollis justo. Suspendisse eu ligula. Nulla "
            + "facilisi. Donec id justo. Praesent porttitor, nulla vitae posuere "
            + "iaculis, arcu nisl dignissim dolor, a pretium mi sem ut ipsum. "
            + "Curabitur suscipit suscipit tellus.\n\n" +

            "Praesent vestibulum dapibus nibh. Etiam iaculis nunc ac metus. Ut id "
            + "nisl quis enim dignissim sagittis. Etiam sollicitudin, ipsum eu "
            + "pulvinar rutrum, tellus ipsum laoreet sapien, quis venenatis ante "
            + "odio sit amet eros. Proin magna. Duis vel nibh at velit scelerisque "
            + "suscipit. Curabitur turpis. Vestibulum suscipit nulla quis orci. Fusce "
            + "ac felis sit amet ligula pharetra condimentum. Maecenas egestas arcu "
            + "quis ligula mattis placerat. Duis lobortis massa imperdiet quam. " + "Suspendisse potenti.\n\n" +

            "Pellentesque commodo eros a enim. Vestibulum turpis sem, aliquet eget, "
            + "lobortis pellentesque, rutrum eu, nisl. Sed libero. Aliquam erat "
            + "volutpat. Etiam vitae tortor. Morbi vestibulum volutpat enim. Aliquam "
            + "eu nunc. Nunc sed turpis. Sed mollis, eros et ultrices tempus, mauris "
            + "ipsum aliquam libero, non adipiscing dolor urna a orci. Nulla porta "
            + "dolor. Class aptent taciti sociosqu ad litora torquent per conubia "
            + "nostra, per inceptos hymenaeos.\n\n" +

            "Pellentesque dapibus hendrerit tortor. Praesent egestas tristique nibh. "
            + "Sed a libero. Cras Objectius. Donec vitae orci sed dolor rutrum auctor. "
            + "Fusce egestas elit eget lorem. Suspendisse nisl elit, rhoncus eget, "
            + "elementum ac, condimentum eget, diam. Nam at tortor in tellus interdum "
            + "sagittis. Aliquam lobortis. Donec orci lectus, aliquam ut, faucibus "
            + "non, euismod id, nulla. Curabitur blandit mollis lacus. Nam adipiscing. " + "Vestibulum eu odio.\n\n" +

            "Vivamus laoreet. Nullam tincidunt adipiscing enim. Phasellus tempus. "
            + "Proin viverra, ligula sit amet ultrices semper, ligula arcu tristique "
            + "sapien, a accumsan nisi mauris ac eros. Fusce neque. Suspendisse "
            + "faucibus, nunc et pellentesque egestas, lacus ante convallis tellus, "
            + "vitae iaculis lacus elit id tortor. Vivamus aliquet elit ac nisl. Fusce "
            + "fermentum odio nec arcu. Vivamus euismod mauris. In ut quam vitae "
            + "odio lacinia tincidunt. Praesent ut ligula non mi Objectius sagittis. "
            + "Cras sagittis. Praesent ac sem eget est egestas volutpat. Vivamus "
            + "consectetuer hendrerit lacus. Cras non dolor. Vivamus in erat ut urna "
            + "cursus vestibulum. Fusce commodo aliquam arcu. Nam commodo suscipit "
            + "quam. Quisque id odio. Praesent venenatis metus at tortor pulvinar " + "varius.\n\n";

    /**
     * Generates a number of paragraphs, with each paragraph surrounded by HTML
     * paragraph tags.
     */
    public String generateParagraphsHtml(int quantity, boolean startWithLorem) {

        return generateMarkupParagraphs("<p>" + LINE_SEPARATOR + "\t", LINE_SEPARATOR + "</p>", LINE_SEPARATOR + "</p>"
                + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR + "\t", quantity, startWithLorem);

    }

    /**
     * Generates a number of paragraphs, with each paragraph surrounded by HTML
     * paragraph tags.
     */
    public void generateParagraphsHtml(Appendable writer, int quantity, boolean startWithLorem) throws IOException {

        generateMarkupParagraphs(writer, "<p>" + LINE_SEPARATOR + "\t", LINE_SEPARATOR + "</p>", LINE_SEPARATOR
                + "</p>" + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR + "\t", quantity, startWithLorem);

    }

    /**
     * Generates one paragraph of HTML, surrounded by HTML paragraph tags.
     */
    public String generateOneParagraphHtml(int quantity, boolean startWithLorem) {

        return generateMarkupSentences("<p>" + LINE_SEPARATOR + "\t", LINE_SEPARATOR + "</p>", LINE_SEPARATOR,
                quantity, startWithLorem);

    }

    /**
     * Generates a number of paragraphs, with each paragraph surrounded by HTML
     * paragraph tags as a full HTML page.
     */
    public String generateParagraphsFullHtml(int quantity, boolean startWithLorem) {

        String prefix = "<html>" + LINE_SEPARATOR + "<header>" + LINE_SEPARATOR + "<title>Lorem Ipsum</title>"
                + LINE_SEPARATOR + "</header>" + LINE_SEPARATOR + LINE_SEPARATOR + "<body>";
        String postfix = "</body>" + LINE_SEPARATOR + "</html>" + LINE_SEPARATOR;

        return generateMarkupParagraphs(prefix + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR + "\t", LINE_SEPARATOR
                + "</p>" + LINE_SEPARATOR + postfix, LINE_SEPARATOR + "</p>" + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR
                + "\t", quantity, startWithLorem);
    }

    /**
     * Generates a number of paragraphs, with each paragraph surrounded by HTML
     * paragraph tags as a full HTML page.
     */
    public void generateParagraphsFullHtml(Appendable writer, int quantity, boolean startWithLorem) throws IOException {

        String prefix = "<html>" + LINE_SEPARATOR + "<header>" + LINE_SEPARATOR + "<title>Lorem Ipsum</title>"
                + LINE_SEPARATOR + "</header>" + LINE_SEPARATOR + LINE_SEPARATOR + "<body>";
        String postfix = "</body>" + LINE_SEPARATOR + "</html>" + LINE_SEPARATOR;

        generateMarkupParagraphs(writer, prefix + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR + "\t", LINE_SEPARATOR
                + "</p>" + LINE_SEPARATOR + postfix, LINE_SEPARATOR + "</p>" + LINE_SEPARATOR + "<p>" + LINE_SEPARATOR
                + "\t", quantity, startWithLorem);
    }

    /**
     * Generates a number of paragraphs, with each paragraph separated by two
     * newlines.
     */
    public String generateParagraphsPlainText(int quantity, boolean startWithLorem) {

        return generateMarkupParagraphs("", "", LINE_SEPARATOR + LINE_SEPARATOR, quantity, startWithLorem);
    }

    /**
     * Generates a number of paragraphs, with each paragraph separated by two
     * newlines.
     */
    public void generateParagraphsPlainText(Appendable writer, int quantity, boolean startWithLorem) throws IOException {

        generateMarkupParagraphs(writer, "", "", LINE_SEPARATOR + LINE_SEPARATOR, quantity, startWithLorem);
    }

    /**
     * Generates a number of paragraphs, with each paragraph separated by two
     * newlines and no line exceeding maxCols columns
     */
    public String generateParagraphsPlainText(int quantity, int maxCols, boolean startWithLorem) {

        StringWriter writer = new StringWriter(quantity + 512);
        try {
            generateParagraphsPlainText(writer, quantity, maxCols, startWithLorem);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return writer.toString();
    }

    /**
     * Generates a number of paragraphs, with each paragraph separated by two
     * newlines and no line exceeding maxCols columns
     */
    public void generateParagraphsPlainText(Appendable writer, int quantity, int maxCols, boolean startWithLorem)
            throws IOException {

        String delims = " .,?!";
        String unformatted = generateMarkupParagraphs("", "", LINE_SEPARATOR + LINE_SEPARATOR, quantity, startWithLorem);
        int len = unformatted.length();

        if (maxCols <= 0) {
            writer.append(unformatted);
        } else {
            int startPos = 0;
            while (startPos < len - 1) {
                int endPos = Math.min(startPos + maxCols, len - 1);
                boolean shift = true;
                // check if there is already a line break:
                for (int i = startPos; i < endPos; i++) {
                    if (unformatted.charAt(i) == '\n') {
                        shift = false;
                        endPos = i;
                    }
                }
                char ch = unformatted.charAt(endPos);
                while (shift) {
                    for (int i = 0; i < delims.length(); i++) {
                        if (ch == delims.charAt(i)) {
                            shift = false;
                            break;
                        }
                    }
                    if (shift) {
                        ch = unformatted.charAt(--endPos);
                        shift = endPos > startPos;
                    }
                }
                writer.append(unformatted.substring(startPos, endPos + 1));
                if (unformatted.charAt(endPos) != '\n') {
                    writer.append(LINE_SEPARATOR);
                }
                startPos = endPos + 1;
            }
        }
    }
}
