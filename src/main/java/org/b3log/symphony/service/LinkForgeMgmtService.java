package org.b3log.symphony.service;

import java.net.URL;
import java.util.List;
import javax.inject.Inject;
import org.b3log.latke.Keys;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.cache.TagCache;
import org.b3log.symphony.model.Link;
import org.b3log.symphony.model.Tag;
import org.b3log.symphony.repository.LinkRepository;
import org.b3log.symphony.repository.TagLinkRepository;
import org.b3log.symphony.repository.TagRepository;
import org.b3log.symphony.util.Links;
import org.b3log.symphony.util.Pangu;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Link utilities.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.0.0.2, Sep 10, 2016
 * @since 1.6.0
 */
@Service
public class LinkForgeMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(LinkForgeMgmtService.class.getName());

    /**
     * Link repository.
     */
    @Inject
    private LinkRepository linkRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Tag-Link repository.
     */
    @Inject
    private TagLinkRepository tagLinkRepository;

    /**
     * Tag cache.
     */
    @Inject
    private TagCache tagCache;

    /**
     * Forges the specified URL.
     *
     * @param url the specified URL
     */
    public void forge(final String url) {
        String html;
        try {
            final Document doc = Jsoup.connect(url).timeout(5000).
                    userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/53.0.2785.101 Safari/537.36").get();

            doc.select("body").append("<a href=\"" + url + "\">" + url + "</a>"); // Append the specified URL itfself

            html = doc.html();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Parses link [" + url + "] failed", e);

            return;
        }

        final List<JSONObject> links = Links.getLinks(html);

        final Transaction transaction = linkRepository.beginTransaction();
        try {
            for (final JSONObject lnk : links) {
                final String addr = lnk.optString(Link.LINK_ADDR);
                JSONObject link = linkRepository.getLink(addr);

                if (null == link) {
                    link = new JSONObject();
                    link.put(Link.LINK_ADDR, lnk.optString(Link.LINK_ADDR));
                    link.put(Link.LINK_BAD_CNT, 0);
                    link.put(Link.LINK_BAIDU_REF_CNT, 0);
                    link.put(Link.LINK_CLICK_CNT, 0);
                    link.put(Link.LINK_GOOD_CNT, 0);
                    link.put(Link.LINK_SCORE, 0);
                    link.put(Link.LINK_SUBMIT_CNT, 0);
                    link.put(Link.LINK_TITLE, lnk.optString(Link.LINK_TITLE));
                    link.put(Link.LINK_TYPE, Link.LINK_TYPE_C_FORGE);

                    linkRepository.add(link);
                } else {
                    link.put(Link.LINK_BAIDU_REF_CNT, lnk.optInt(Link.LINK_BAIDU_REF_CNT));
                    link.put(Link.LINK_TITLE, lnk.optString(Link.LINK_TITLE));
                    link.put(Link.LINK_SCORE, lnk.optInt(Link.LINK_BAIDU_REF_CNT)); // XXX: Need a score algorithm

                    linkRepository.update(link.optString(Keys.OBJECT_ID), link);
                }

                final String linkId = link.optString(Keys.OBJECT_ID);
                final double linkScore = link.optDouble(Link.LINK_SCORE);
                String title = link.optString(Link.LINK_TITLE) + " " + link.optString(Link.LINK_T_KEYWORDS);
                title = Pangu.spacingText(title);
                String[] titles = title.split(" ");
                titles = Strings.trimAll(titles);

                final List<JSONObject> cachedTags = tagCache.getIconTags(Integer.MAX_VALUE);
                for (final JSONObject cachedTag : cachedTags) {
                    final String tagId = cachedTag.optString(Keys.OBJECT_ID);
                    final JSONObject tag = tagRepository.get(tagId);

                    // clean
                    final int removedRelCnt = tagLinkRepository.removeByLinkIdAndTagId(linkId, tagId);
                    int tagLinkCnt = tag.optInt(Tag.TAG_LINK_CNT) - removedRelCnt;
                    if (tagLinkCnt < 0) {
                        tagLinkCnt = 0;
                    }
                    tag.put(Tag.TAG_LINK_CNT, tagLinkCnt);
                    tagRepository.update(tagId, tag);

                    final String tagTitle = tag.optString(Tag.TAG_TITLE);
                    if (!Strings.containsIgnoreCase(tagTitle, titles)) {
                        continue;
                    }

                    // re-add
                    final JSONObject tagLinkRel = new JSONObject();
                    tagLinkRel.put(Tag.TAG_T_ID, tagId);
                    tagLinkRel.put(Link.LINK_T_ID, linkId);
                    tagLinkRel.put(Link.LINK_SCORE, linkScore);
                    tagLinkRepository.add(tagLinkRel);

                    tag.put(Tag.TAG_LINK_CNT, tag.optInt(Tag.TAG_LINK_CNT) + 1);
                    tagRepository.update(tagId, tag);
                }
            }

            transaction.commit();

            LOGGER.info("Forged link [" + url + "]");
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Saves links failed", e);
        }
    }
}
