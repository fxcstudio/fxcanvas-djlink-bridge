package studio.fxc.djlink.model;

/**
 * The track metadata the bridge reports for one player, independent of
 * beat-link types so the publishing rules can be tested without a network.
 *
 * Strings that rekordbox did not supply are {@code null}; the publisher skips
 * those messages (the open-beat-control rule for searchable items).
 */
public final class TrackInfo {
    public final int trackId;
    public final String title;
    public final String artist;
    public final String album;
    public final String color;
    public final String comment;
    public final int duration;
    public final String genre;
    public final String key;
    public final String label;
    public final String originalArtist;
    public final int rating;
    public final String remixer;
    public final float tempo;

    public TrackInfo(int trackId, String title, String artist, String album, String color, String comment,
                     int duration, String genre, String key, String label, String originalArtist, int rating,
                     String remixer, float tempo) {
        this.trackId = trackId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.color = color;
        this.comment = comment;
        this.duration = duration;
        this.genre = genre;
        this.key = key;
        this.label = label;
        this.originalArtist = originalArtist;
        this.rating = rating;
        this.remixer = remixer;
        this.tempo = tempo;
    }

    /** A track with only a title and artist, for tests. */
    public static TrackInfo basic(int trackId, String title, String artist) {
        return new TrackInfo(trackId, title, artist, null, "", "", 0, null, null, null, null, 0, null, 0f);
    }
}
